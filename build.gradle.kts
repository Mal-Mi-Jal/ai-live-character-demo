plugins {
    java
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.livecharacter"
version = "0.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    // 캐릭터 대사 말투 (Claude Haiku). 키는 환경변수 ANTHROPIC_API_KEY로만 받는다
    implementation("com.anthropic:anthropic-java:3.2.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
    // -Dhaiku=true 일 때만 유료 실측 테스트(HaikuProbe)가 돈다
    systemProperty("haiku", System.getProperty("haiku", "false"))
}
