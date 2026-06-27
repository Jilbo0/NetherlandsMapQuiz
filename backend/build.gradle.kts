plugins {
	kotlin("jvm") version "2.1.10"
	id("io.ktor.plugin") version "3.1.0"
	application
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("io.ktor:ktor-server-core-jvm:3.1.0")
	implementation("io.ktor:ktor-server-netty-jvm:3.1.0")
	implementation("io.ktor:ktor-server-content-negotiation-jvm:3.1.0")
	implementation("io.ktor:ktor-serialization-jackson-jvm:3.1.0")
	implementation("io.ktor:ktor-server-cors-jvm:3.1.0")
	implementation("io.ktor:ktor-server-call-logging-jvm:3.1.0")
	implementation("com.squareup.okhttp3:okhttp:4.12.0")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
	implementation("org.slf4j:slf4j-simple:2.0.13")
	testImplementation("io.ktor:ktor-server-test-host:3.1.0")
	testImplementation(kotlin("test"))
}

application {
	mainClass.set("backend.ApplicationKt")
}

sourceSets {
	main {
		kotlin {
			srcDirs("src/main/kotlin")
		}
	}
	test {
		kotlin {
			srcDirs("src/test/kotlin")
		}
	}
}

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(23))
	}
}

tasks.test {
	useJUnitPlatform()
}