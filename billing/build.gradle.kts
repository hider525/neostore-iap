plugins {
    id("com.android.library")
    id("maven-publish")
}

val publicationGroup = providers.environmentVariable("GROUP").orElse("com.github.hider525")
val publicationArtifact = providers.environmentVariable("ARTIFACT").orElse("neostore-iap")
val publicationVersion = providers.environmentVariable("VERSION").orElse("0.1")

group = publicationGroup.get()
version = publicationVersion.get()

android {
    namespace = "com.neostudio.billing"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testFixtures {
        enable = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api("androidx.activity:activity-ktx:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = publicationGroup.get()
                artifactId = publicationArtifact.get()
                version = project.version.toString()
            }
        }
    }
}
