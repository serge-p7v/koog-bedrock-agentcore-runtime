plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "koog-bedrock-agentcore-runtime-example"

// Composite build — pulls the plugin source from the sibling parent project so the
// example always builds against the local plugin version (no Maven publish required).
includeBuild("..")
