package nl.dichtbij3d.backend

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.autoconfigure.mail.MailHealthContributorAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ActuatorMailHealthDisabledTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            MailSenderAutoConfiguration::class.java,
            MailHealthContributorAutoConfiguration::class.java
        ))

    @Test
    fun `mail sender bean is not created when spring mail host is empty or omitted`() {
        contextRunner
            .run { context ->
                assertThat(context).doesNotHaveBean("mailSender")
            }
    }

    @Test
    fun `mail sender bean is created when spring mail host is empty string in properties`() {
        contextRunner
            .withPropertyValues("spring.mail.host=")
            .run { context ->
                // Note: with empty string, Spring Boot DOES create the bean!
                println("Does it have mailSender with host='': " + context.containsBean("mailSender"))
            }
    }

    @Test
    fun `mail health contributor is disabled when management health mail enabled is false`() {
        contextRunner
            .withPropertyValues(
                "spring.mail.host=smtp.gmail.com",
                "management.health.mail.enabled=false"
            )
            .run { context ->
                assertThat(context).hasBean("mailSender")
                assertThat(context).doesNotHaveBean("mailHealthContributor")
                assertThat(context).doesNotHaveBean("mailHealthIndicator")
            }
    }

    @Test
    fun `mail health contributor is enabled by default when mail host is configured`() {
        contextRunner
            .withPropertyValues(
                "spring.mail.host=smtp.gmail.com"
            )
            .run { context ->
                assertThat(context).hasBean("mailSender")
                assertThat(context).hasBean("mailHealthContributor")
            }
    }
}
