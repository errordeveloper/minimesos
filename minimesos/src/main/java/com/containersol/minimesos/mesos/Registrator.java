package com.containersol.minimesos.mesos;

import com.containersol.minimesos.cluster.MesosCluster;
import com.containersol.minimesos.config.ConsulConfig;
import com.containersol.minimesos.config.RegistratorConfig;
import com.containersol.minimesos.container.AbstractContainer;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.Bind;

/**
 * Registrator automatically registers and deregisters services for any Docker container by inspecting containers as they come online.
 */
public class Registrator extends AbstractContainer {

    private final RegistratorConfig config;
    private Consul consulContainer;

    public Registrator(Consul consulContainer, RegistratorConfig config) {
        super();
        this.consulContainer = consulContainer;
        this.config = config;
    }

    public Registrator(MesosCluster cluster, String uuid, String containerId) {
        this(cluster, uuid, containerId, new RegistratorConfig());
    }

    private Registrator(MesosCluster cluster, String uuid, String containerId, RegistratorConfig config) {
        super(cluster, uuid, containerId);
        this.config = config;
    }

    @Override
    public String getRole() {
        return "registrator";
    }

    @Override
    protected void pullImage() {
        pullImage(config.getImageName(), config.getImageTag());
    }

    @Override
    protected CreateContainerCmd dockerCommand() {
        return DockerClientFactory.build().createContainerCmd(config.getImageName() + ":" + config.getImageTag())
                .withNetworkMode("host")
                .withBinds(Bind.parse("/var/run/docker.sock:/tmp/docker.sock"))
                .withCmd("-internal", String.format("consul://%s:%d", consulContainer.getIpAddress(), ConsulConfig.CONSUL_HTTP_PORT))
                .withName(getName());
    }

}
