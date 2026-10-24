plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":agent-api"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation(libs.docker.java.core)
    implementation(libs.docker.java.httpclient5)
    implementation(libs.oshi.core)
    implementation(libs.bcpkix)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(libs.postgresql)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar {
    archiveFileName = "pgcp-agent.jar"
}

// Real-Docker tests are slow and need a Docker daemon; run them with -PdockerTests.
tasks.test {
    if (!project.hasProperty("dockerTests")) {
        exclude("**/*DockerIT*")
    }
}
