package com.example.iotlab.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** Mosquitto efímero, misma versión que docker-compose.yml. */
public class MosquittoContainer extends GenericContainer<MosquittoContainer> {

    public static final String IMAGE = "eclipse-mosquitto:2.0.22";

    public MosquittoContainer() {
        super(IMAGE);
        withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto-test.conf"),
                "/mosquitto/config/mosquitto.conf");
        withExposedPorts(1883);
        // Mosquitto no vacía stdout sin TTY: esperar por log no sirve.
        waitingFor(Wait.forListeningPort());
    }

    public String serverUri() {
        return "tcp://" + getHost() + ":" + getMappedPort(1883);
    }
}
