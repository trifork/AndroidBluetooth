# Android Bluetooth

## How to install

### Step 1: Add the JitPack maven repository to the list of repositories:

```gradle
repositories {
	jcenter()
	maven { url "https://jitpack.io" }
}
```

### Step 2: Add the dependency information:

```gradle
dependencies {
	implementation 'com.github.trifork:AndroidBluetooth:1.0.0' // check the releases/tags page to find the latest version
}
```

## How to use

Create an instance of the `BluetoothManager` for injection.

```
    BluetoothManager(
        applicationContext,
        BluetoothManager.Configuration(
            connectionMtuSize = 200, // optional
            logger = object : BluetoothManager.Logger {  // optional
                override fun v(message: String) {
                    Timber.tag(TAG).v(message) // or, your logging framework of choice
                }

                override fun d(message: String) {
                    Timber.tag(TAG).d(message)
                }

                override fun i(message: String) {
                    Timber.tag(TAG).i(message)
                }

                override fun w(message: String) {
                    Timber.tag(TAG).w(message)
                }

                override fun e(message: String) {
                    Timber.tag(TAG).e(message)
                }
            },
            hexFormatter = { bytes -> bytes.joinToString("-") { "%02x".format(it) } } // optional
        )
    )
```

With this instance you can:
 - start scan
 - stop scan
 - connect
 - read the rssi of a connection
 - set the priority of a connection
 - add and remove individual listeners to connections
 - enable notification for a characteristic on a connection
 - read a characteristic on a connection
 - write bytes to a characteristic on a connection

## How to deploy new version

This library is distributed with using JitPack.io.

### Step 1:

Go to the [releases page](https://github.com/trifork/AndroidBluetooth/releases).

### Step 2:

Select "Draft a new release"

### Step 3:

Set a tag version. For example `v1.0.1`
Select @target to be the `main` branch.
Provide a release title, if it should not be the tag version provided.

### Step 4:

Press "Publish release"

This will create a release and a corresponding tag.

🎉 Hurray! It is now available to users to start using in their Android projects.