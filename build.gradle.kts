plugins {
    id("java")
}

group = "net.hapore"
version = "1.0.1"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(files("lib/HytaleServer.jar"))
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}