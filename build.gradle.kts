// Force patched versions of the Android Gradle Plugin's transitive BUILD-CLASSPATH deps
// (gRPC / Google-Cloud tooling AGP bundles — never shipped in the APK). All minor/patch bumps
// that close the open HIGH Dependabot advisories without touching app runtime deps.
buildscript {
    configurations.classpath {
        resolutionStrategy.force(
            "io.netty:netty-handler:4.1.135.Final",
            "io.netty:netty-codec:4.1.135.Final",
            "io.netty:netty-codec-http:4.1.135.Final",
            "io.netty:netty-codec-http2:4.1.135.Final",
            "com.google.protobuf:protobuf-java:3.25.5",
            "com.google.protobuf:protobuf-kotlin:3.25.5",
            "org.bouncycastle:bcpg-jdk18on:1.84",
            "org.bitbucket.b_c:jose4j:0.9.6",
            "org.jdom:jdom2:2.0.6.1",
        )
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
