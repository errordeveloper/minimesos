package com.containersol.minimesos.cluster;

import com.containersol.minimesos.MinimesosException;
import com.containersol.minimesos.config.AppConfig;
import com.containersol.minimesos.config.ClusterConfig;
import com.containersol.minimesos.config.ConsulConfig;
import com.containersol.minimesos.config.MarathonConfig;
import com.containersol.minimesos.config.MesosMasterConfig;
import com.containersol.minimesos.container.AbstractContainer;
import com.containersol.minimesos.container.ContainerName;
import com.containersol.minimesos.marathon.Marathon;
import com.containersol.minimesos.mesos.ClusterArchitecture;
import com.containersol.minimesos.mesos.ClusterContainers;
import com.containersol.minimesos.mesos.ClusterUtil;
import com.containersol.minimesos.mesos.Consul;
import com.containersol.minimesos.mesos.DockerClientFactory;
import com.containersol.minimesos.mesos.MesosAgent;
import com.containersol.minimesos.mesos.MesosMaster;
import com.containersol.minimesos.mesos.Registrator;
import com.containersol.minimesos.mesos.ZooKeeper;
import com.containersol.minimesos.state.State;
import com.containersol.minimesos.util.Predicate;
import com.github.dockerjava.api.InternalServerErrorException;
import com.github.dockerjava.api.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.jayway.awaitility.Awaitility;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.json.JSONObject;
import org.junit.rules.ExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Mesos cluster with lifecycle methods such as start, install, info, state, stop and destroy.
 */
public class MesosCluster extends ExternalResource {

    private static Logger LOGGER = LoggerFactory.getLogger(MesosCluster.class);

    public static final String MINIMESOS_HOST_DIR_PROPERTY = "minimesos.host.dir";

    private String clusterId;

    private final ClusterConfig clusterConfig;

    private List<AbstractContainer> containers = Collections.synchronizedList(new ArrayList<>());

    private boolean running = false;

    /**
     * Create a new MesosCluster with a specified cluster architecture.
     *
     * @param clusterArchitecture Represents the layout of the cluster. See {@link ClusterArchitecture} and {@link ClusterUtil}
     */
    public MesosCluster(ClusterArchitecture clusterArchitecture) {
        if (clusterArchitecture == null) {
            throw new ClusterArchitecture.MesosArchitectureException("No cluster architecture specified");
        }

        this.containers = clusterArchitecture.getClusterContainers().getContainers();
        this.clusterConfig = clusterArchitecture.getClusterConfig();

        clusterId = Integer.toUnsignedString(new SecureRandom().nextInt());
        for (AbstractContainer container : containers) {
            container.setCluster(this);
        }
    }

    /**
     * Recreate a MesosCluster object based on an existing cluster ID.
     *
     * @param clusterId the cluster ID of the cluster that is already running
     */
    public static MesosCluster loadCluster(String clusterId) {
        return new MesosCluster(clusterId);
    }

    /**
     * This constructor is used for deserialization of running cluster
     *
     * @param clusterId ID of the cluster to deserialize
     */
    private MesosCluster(String clusterId) {
        this.clusterId = clusterId;
        this.clusterConfig = new ClusterConfig();

        List<Container> dockerContainers = DockerClientFactory.build().listContainersCmd().exec();
        Collections.sort(dockerContainers, (c1, c2) -> Long.compare(c1.getCreated(), c2.getCreated()));

        ZooKeeper zookeeper = null;

        for (Container container : dockerContainers) {
            String name = ContainerName.getFromDockerNames(container.getNames());
            if (ContainerName.belongsToCluster(name, clusterId)) {

                String containerId = container.getId();
                String[] parts = name.split("-");
                String role = parts[1];
                String uuid = parts[3];

                switch (role) {
                    case "zookeeper":
                        zookeeper = new ZooKeeper(this, uuid, containerId);
                        this.containers.add(zookeeper);
                        break;
                    case "agent":
                        this.containers.add(new MesosAgent(this, uuid, containerId));
                        break;
                    case "master":
                        MesosMaster master = new MesosMaster(this, uuid, containerId);
                        this.containers.add(master);
                        // restore "exposed ports" attribute
                        Container.Port[] ports = container.getPorts();
                        if (ports != null) {
                            for (Container.Port port : ports) {
                                if (port.getIp() != null && port.getPrivatePort() == MesosMasterConfig.MESOS_MASTER_PORT) {
                                    setExposedHostPorts(true);
                                }
                            }
                        }
                        break;
                    case "marathon":
                        this.containers.add(new Marathon(this, uuid, containerId));
                        break;
                    case "consul":
                        this.containers.add(new Consul(this, uuid, containerId));
                        break;
                    case "registrator":
                        this.containers.add(new Registrator(this, uuid, containerId));
                        break;
                }

            }

        }

        if (containers.isEmpty()) {
            throw new MinimesosException("No containers found for cluster ID " + clusterId);
        }

        if (zookeeper != null) {
            for (MesosAgent mesosAgent : getAgents()) {
                mesosAgent.setZooKeeperContainer(zookeeper);
            }
            getMasterContainer().setZooKeeperContainer(zookeeper);

            if (getMarathonContainer() != null) {
                getMarathonContainer().setZooKeeper(zookeeper);
            }
        }

        running = true;
    }

    /**
     * Starts the Mesos cluster and its containers with 60 second timeout.
     * The method is used by frameworks
     */
    public void start() {
        start(clusterConfig.getTimeout());
    }

    /**
     * Starts the Mesos cluster and its containers with given timeout.
     *
     * @param timeoutSeconds seconds to wait until timeout
     */
    public void start(int timeoutSeconds) {
        if (running) {
            throw new IllegalStateException("Cluster " + clusterId + " is already running");
        }

        LOGGER.debug("Cluster " + getClusterId() + " - start");
        this.containers.forEach((container) -> container.start(timeoutSeconds));
        // wait until the given number of agents are registered
        getMasterContainer().waitFor();

        installMarathonApps();

        running = true;
    }

    /**
     * If Marathon configuration requires, installs the applications
     */
    protected void installMarathonApps() {
        Marathon marathon = getMarathonContainer();
        if (marathon != null) {

            marathon.waitFor();

            List<AppConfig> apps = marathon.getConfig().getApps();
            for (AppConfig app : apps) {
                try {

                    InputStream json = getInputStream(app.getMarathonJson());
                    if (json != null) {
                        marathon.deployApp(IOUtils.toString(json, "UTF-8"));
                    } else {
                        throw new MinimesosException("Failed to find content of " + app.getMarathonJson());
                    }

                } catch (IOException ioe) {
                    throw new MinimesosException("Failed to load JSON from " + app.getMarathonJson(), ioe);
                }
            }

        }
    }


    /**
     * Print cluster info
     */
    public void info(PrintStream out) {
        if (clusterId != null) {
            out.println("Minimesos cluster is running: " + clusterId);
            if (getMesosVersion() != null) {
                out.println("Mesos version: " + clusterConfig.getMesosVersion());
            }
            printServiceUrls(out);
        }
    }

    /**
     * Prints the state of the Mesos master or agent
     */
    public void state(PrintStream out, String agentContainerId) {
        JSONObject stateInfo;
        if (StringUtils.isEmpty(agentContainerId)) {
            stateInfo = getClusterStateInfo();
        } else {
            stateInfo = getAgentStateInfo(agentContainerId);
        }

        if (stateInfo != null) {
            out.println(stateInfo.toString(2));
        } else {
            throw new MinimesosException("Did not find the cluster or requested container");
        }
    }

    /**
     * Stops the Mesos cluster and its containers.
     * Containers are stopped in reverse order of their creation
     */
    public void stop() {
        LOGGER.debug("Cluster " + getClusterId() + " - stop");

        if (containers.size() > 0) {
            for (int i = containers.size() - 1; i >= 0; i--) {
                AbstractContainer container = containers.get(i);
                LOGGER.debug("Removing container [" + container.getContainerId() + "]");
                try {
                    container.remove();
                } catch (NotFoundException e) {
                    LOGGER.error(String.format("Cannot remove container %s, maybe it's already dead?", container.getContainerId()));
                }
            }
        }
        this.running = false;
        this.containers.clear();
    }

    /**
     * Installs a Marathon app
     *
     * @param marathonJson JSON representation of Marathon app
     */
    public void install(String marathonJson) {
        if (marathonJson == null) {
            throw new MinimesosException("Specify a Marathon JSON app definition");
        }

        Marathon marathon = getMarathonContainer();
        if (marathon == null) {
            throw new MinimesosException("Marathon container is not found in cluster " + clusterId);
        }

        String marathonIp = marathon.getIpAddress();
        LOGGER.debug(String.format("Installing %s app on marathon %s", marathonJson, marathonIp));

        marathon.deployApp(marathonJson);
    }

    /**
     * Destroys the Mesos cluster and its containers
     */
    public void destroy() {
        LOGGER.debug("Cluster " + getClusterId() + " - destroy");
        Marathon marathon = getMarathonContainer();
        if (marathon != null) {
            marathon.killAllApps();
        }

        List<Container> containers = DockerClientFactory.build().listContainersCmd().exec();
        for (Container container : containers) {
            if (ContainerName.belongsToCluster(container.getNames(), clusterId)) {
                DockerClientFactory.build().removeContainerCmd(container.getId()).withForce().withRemoveVolumes(true).exec();
            }
        }
        File sandboxLocation = new File(getHostDir(), ".minimesos/sandbox-" + clusterId);
        if (sandboxLocation.exists()) {
            try {
                FileUtils.forceDelete(sandboxLocation);
            } catch (IOException e) {
                String msg = String.format("Failed to force delete the cluster sandbox at %s", sandboxLocation.getAbsolutePath());
                LOGGER.error(msg, e);
                throw new MinimesosException(msg, e);
            }
        }
    }

    /**
     * Starts a container. This container will be removed when the Mesos cluster is shut down.
     *
     * @param container container to be started
     * @param timeout   in seconds
     * @return container ID
     */
    public String addAndStartContainer(AbstractContainer container, int timeout) {
        container.setCluster(this);
        containers.add(container);

        LOGGER.debug(String.format("Starting %s (%s) container", container.getName(), container.getContainerId()));

        try {
            container.start(timeout);
        } catch (Exception exc) {
            String msg = String.format("Failed to start %s (%s) container", container.getName(), container.getContainerId());
            LOGGER.error(msg, exc);
            throw new MinimesosException(msg, exc);
        }

        return container.getContainerId();
    }

    /**
     * Starts a container. This container will be removed when the Mesos cluster is shut down.
     * The method is used by frameworks
     *
     * @param container container to be started
     * @return container ID
     */
    public String addAndStartContainer(AbstractContainer container) {
        return addAndStartContainer(container, clusterConfig.getTimeout());
    }

    /**
     * Retrieves JSON with Mesos Cluster master state
     *
     * @return stage JSON
     */
    public JSONObject getClusterStateInfo() {
        try {
            return getMasterContainer().getStateInfoJSON();
        } catch (UnirestException e) {
            throw new MinimesosException("Failed to retrieve state from Mesos Master", e);
        }
    }

    /**
     * Retrieves JSON with Mesos state of the given container
     *
     * @param containerId ID of the container to get state from
     * @return stage JSON
     */
    public JSONObject getAgentStateInfo(String containerId) {
        MesosAgent theAgent = null;
        for (MesosAgent agent : getAgents()) {
            if (agent.getContainerId().startsWith(containerId)) {
                if (theAgent == null) {
                    theAgent = agent;
                } else {
                    throw new MinimesosException("Provided ID " + containerId + " is not enough to uniquely identify container");
                }

            }
        }

        try {
            return (theAgent != null) ? theAgent.getStateInfoJSON() : null;
        } catch (UnirestException e) {
            throw new MinimesosException("Failed to retrieve state from Mesos Agent container " + theAgent.getContainerId(), e);
        }
    }

    @Override
    protected void before() throws Throwable {
        start();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                destroyContainers(clusterId);
            }
        });
    }

    private static void destroyContainers(String clusterId) {
        List<Container> containers = DockerClientFactory.build().listContainersCmd().exec();
        for (Container container : containers) {
            if (ContainerName.belongsToCluster(container.getNames(), clusterId)) {
                DockerClientFactory.build().removeContainerCmd(container.getId()).withForce().withRemoveVolumes(true).exec();
            }
        }
        LOGGER.info("Destroyed minimesos cluster " + clusterId);
    }

    public List<AbstractContainer> getContainers() {
        return containers;
    }

    public List<MesosAgent> getAgents() {
        return containers.stream().filter(ClusterContainers.Filter.mesosAgent()).map(c -> (MesosAgent) c).collect(Collectors.toList());
    }

    @Override
    protected void after() {
        stop();
    }

    public MesosMaster getMasterContainer() {
        Optional<MesosMaster> master = getOne(ClusterContainers.Filter.mesosMaster());
        return master.isPresent() ? master.get() : null;
    }

    public ZooKeeper getZkContainer() {
        Optional<ZooKeeper> zooKeeper = getOne(ClusterContainers.Filter.zooKeeper());
        return zooKeeper.isPresent() ? zooKeeper.get() : null;
    }

    public Marathon getMarathonContainer() {
        Optional<Marathon> marathon = getOne(ClusterContainers.Filter.marathon());
        return marathon.isPresent() ? marathon.get() : null;
    }

    public Consul getConsulContainer() {
        Optional<Consul> container = getOne(ClusterContainers.Filter.consul());
        return container.isPresent() ? container.get() : null;
    }

    /**
     * Optionally get one of a certain type of type T. Note, this cast will always work because we are filtering on that type.
     * If it doesn't find that type, the optional is empty so the cast doesn't need to be performed.
     *
     * @param filter A predicate that is true when an {@link AbstractContainer} in the list is of type T
     * @param <T>    A container of type T that extends {@link AbstractContainer}
     * @return the first container it comes across.
     */
    @SuppressWarnings("unchecked")
    public <T extends AbstractContainer> Optional<T> getOne(java.util.function.Predicate<AbstractContainer> filter) {
        return (Optional<T>) getContainers().stream().filter(filter).findFirst();
    }

    public String getClusterId() {
        return clusterId;
    }

    public boolean isExposedHostPorts() {
        return clusterConfig.getExposePorts();
    }

    public boolean getMapAgentSandboxVolume() {
        return clusterConfig.getMapAgentSandboxVolume();
    }

    public void setExposedHostPorts(boolean exposedHostPorts) {
        clusterConfig.setExposePorts(exposedHostPorts);
    }

    public void waitForState(final Predicate<State> predicate) {
        Awaitility.await().atMost(clusterConfig.getTimeout(), TimeUnit.SECONDS).until(() -> {
            try {
                return predicate.test(State.fromJSON(getMasterContainer().getStateInfoJSON().toString()));
            } catch (InternalServerErrorException e) {
                LOGGER.error(e.toString());
                // This probably means that the mesos cluster isn't ready yet..
                return false;
            }
        });
    }

    public void printServiceUrls(PrintStream out) {
        boolean exposedHostPorts = isExposedHostPorts();
        String dockerHostIp = System.getenv("DOCKER_HOST_IP");

        for (AbstractContainer container : getContainers()) {

            String ip;
            if (!exposedHostPorts || StringUtils.isEmpty(dockerHostIp)) {
                ip = container.getIpAddress();
            } else {
                ip = dockerHostIp;
            }

            switch (container.getRole()) {
                case "master":
                    out.println("export MINIMESOS_MASTER=http://" + ip + ":" + MesosMasterConfig.MESOS_MASTER_PORT);
                    break;
                case "marathon":
                    out.println("export MINIMESOS_MARATHON=http://" + ip + ":" + MarathonConfig.MARATHON_PORT);
                    break;
                case "zookeeper":
                    out.println("export MINIMESOS_ZOOKEEPER=" + ZooKeeper.getFormattedZKAddress(ip));
                    break;
                case "consul":
                    out.println("export MINIMESOS_CONSUL=http://" + ip + ":" + ConsulConfig.CONSUL_HTTP_PORT);
                    out.println("export MINIMESOS_CONSUL_IP=" + ip);
                    break;
            }

        }
    }

    /**
     * Returns current user directory, which is mapped to host
     *
     * @return container directory, which is mapped to current directory on host
     */
    public static File getHostDir() {
        String sp = System.getProperty(MINIMESOS_HOST_DIR_PROPERTY);
        if (sp == null) {
            sp = System.getProperty("user.dir");
        }
        return new File(sp);
    }

    /**
     * Taking either URI or path to a file, returns string with its content
     *
     * @param location either absolute URI or path to a file
     *
     * @return input stream with location content or null
     */
    public static InputStream getInputStream(String location) {

        InputStream is = null;

        if (location != null) {

            URI uri = null;
            try {
                uri = URI.create(location);
                if (!uri.isAbsolute()) {
                    uri = null;
                }
            } catch (IllegalArgumentException ignored) {
                // means this is not a valid URI
            }

            if (uri != null) {

                try {
                    is = uri.toURL().openStream();
                } catch (IOException e) {
                    throw new MinimesosException("Failed to open " + location + " as URL", e);
                }

            } else {
                // location is not an absolute URI, therefore treat it as relative or absolute path
                File file = new File(location);
                if (!file.exists()) {
                    file = new File(getHostDir(), location);
                }

                if (file.exists()) {
                    try {
                        is = new FileInputStream(file);
                    } catch (FileNotFoundException e) {
                        throw new MinimesosException("Failed to open " + file.getAbsolutePath() + " file", e);
                    }
                }
            }

        }

        return is;
    }

    /**
     * @return configured or default logging level of all Mesos containers in the cluster
     */
    public String getLoggingLevel() {
        return clusterConfig.getLoggingLevel();
    }

    /**
     * @return configured or default Mesos version of all Mesos containers in the cluster
     */
    public String getMesosVersion() {
        return clusterConfig.getMesosVersion();
    }

    /**
     * @return either configured or composed with ID cluster name
     */
    public String getClusterName() {
        String name = clusterConfig.getClusterName();
        if (StringUtils.isBlank(name)) {
            name = "minimesos-" + clusterId;
        }
        return name;
    }

    public ClusterConfig getClusterConfig() {
        return clusterConfig;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MesosCluster cluster = (MesosCluster) o;

        return clusterId.equals(cluster.clusterId);
    }

    @Override
    public int hashCode() {
        // logic of hashCode() has to match logic of equals()
        return clusterId.hashCode();
    }

    @Override
    public String toString() {
        return "MesosCluster{" +
                "clusterId='" + clusterId + '\'' +
                ", containers=" + containers +
                '}';
    }

}
