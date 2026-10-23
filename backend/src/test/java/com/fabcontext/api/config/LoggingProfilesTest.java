package com.fabcontext.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LoggingProfilesTest {
 private final ApplicationContextRunner context=new ApplicationContextRunner()
  .withInitializer(new ConfigDataApplicationContextInitializer());
 @Test void devEnablesOnlyApplicationDebug() {
  context.withPropertyValues("spring.profiles.active=dev").run(c->{
   assertThat(c.getEnvironment().getProperty("logging.level.com.fabcontext")).isEqualTo("DEBUG");
   assertThat(c.getEnvironment().getProperty("logging.level.root")).isEqualTo("INFO");
   assertThat(c.getEnvironment().getProperty("logging.level.org.hibernate.orm.jdbc.bind")).isEqualTo("OFF");
  });
 }
 @Test void prodAndDefaultUseInfoAndAllowExplicitOverride() {
  context.withPropertyValues("spring.profiles.active=prod").run(c->assertThat(c.getEnvironment().getProperty("logging.level.com.fabcontext")).isEqualTo("INFO"));
  context.run(c->assertThat(c.getEnvironment().getProperty("logging.level.com.fabcontext")).isEqualTo("INFO"));
  context.withPropertyValues("spring.profiles.active=prod","APP_LOG_LEVEL=WARN").run(c->assertThat(c.getEnvironment().getProperty("logging.level.com.fabcontext")).isEqualTo("WARN"));
 }
}
