package nl.dichtbij3d.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class Dichtbij3dApplication

fun main(args: Array<String>) {
    runApplication<Dichtbij3dApplication>(*args)
}
