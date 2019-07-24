# Use

Add this code in your root `build.gradle`

```groovy
allprojects {
  repositories {
    maven { url 'https://jitpack.io' }
  }
}
```

And then add dependency
```groovy
dependencies {
  implementation 'com.github.Pastor:sqlg2:4.0'
}
```