package il.ac.technion.cs.softwaredesign

import com.google.inject.Guice
import dev.misfitlabs.kotlinguice4.KotlinModule
import dev.misfitlabs.kotlinguice4.getInstance
import il.ac.technion.cs.softwaredesign.storage.SecureStorageFactory
import il.ac.technion.cs.softwaredesign.storage.SecureStorageModule
import java.nio.charset.Charset

class SimpleDBModule : KotlinModule() {
    override fun configure() {
        install(SecureStorageModule())
        bind<Charset>().toInstance(Charsets.UTF_8)
    }
}