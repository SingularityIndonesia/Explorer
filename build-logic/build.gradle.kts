plugins {
    `kotlin-dsl`
}

dependencies {
    // Both are on the classpath already when the app applies this plugin next to AGP.
    compileOnly("com.android.tools.build:gradle-api:${libs.versions.agp.get()}")
    compileOnly("org.ow2.asm:asm-commons:9.9")
}

gradlePlugin {
    plugins {
        register("callTrace") {
            id = "explorer.call-trace"
            implementationClass = "CallTracePlugin"
        }
    }
}
