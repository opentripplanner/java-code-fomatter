plugins {
  id("java")
  id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "org.tiger.java.code"
version = providers.gradleProperty("formatterVersion").get()

repositories {
  mavenCentral()
  // the formatter, installed by `mvn install` in the repository root
  mavenLocal()

  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  implementation("org.tiger.java.code:formatter:${providers.gradleProperty("formatterVersion").get()}")

  // the IDE provides these at run time; newer IDEs no longer put them on the compile classpath
  compileOnly("org.jetbrains:annotations:26.0.2")

  intellijPlatform {
    val localIde = providers.gradleProperty("localIde")

    if (localIde.isPresent) {
      local(localIde.get())
    } else {
      intellijIdea(providers.gradleProperty("platformVersion").get())
    }
  }
}

intellijPlatform {
  pluginConfiguration {
    ideaVersion {
      sinceBuild = "262"
    }
  }
  // the plugin has no settings pages to index
  buildSearchableOptions = false
}

tasks.compileJava {
  options.release = 25
}
