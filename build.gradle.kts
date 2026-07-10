// Force patched versions of the Android Gradle Plugin's transitive BUILD-CLASSPATH deps
// (gRPC / Google-Cloud tooling AGP bundles — never shipped in the APK). All minor/patch bumps
// that close the open HIGH Dependabot advisories without touching app runtime deps.
buildscript {
    configurations.classpath {
        resolutionStrategy {
            // Pin EVERY netty module to one patched version. Forcing only a subset (codec,
            // handler, …) would leave netty-common/buffer/transport/resolver on the old version
            // and skew the set, risking linkage errors — so bump the whole io.netty group.
            eachDependency {
                if (requested.group == "io.netty") useVersion("4.1.135.Final")
            }
            force(
                "com.google.protobuf:protobuf-java:3.25.5",
                "com.google.protobuf:protobuf-kotlin:3.25.5",
                "org.bouncycastle:bcpg-jdk18on:1.84",
                "org.bitbucket.b_c:jose4j:0.9.6",
                "org.jdom:jdom2:2.0.6.1",
            )
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
