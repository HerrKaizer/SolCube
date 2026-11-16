// Compile-time stand-ins for the hidden (@SystemApi) audio-policy classes. Only compiled against
// (compileOnly), never packaged: at runtime the phone's own framework classes are used.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
