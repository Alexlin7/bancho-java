package cc.alexlin7.bancho.irc.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.ClassUtils;

import cc.alexlin7.bancho.irc.OsuIrcClient;

/**
 * 使用端的其他依賴帶入 jakarta.validation-api、卻沒有任何實作時，仍要能啟動。
 * <p>
 * Bean Validation 找不到實作時會退回自己的 classloader 再找一次，同一個 JVM 裡無法用 FilteredClassLoader 模擬，
 * 所以由 pom 中 surefire 的 {@code without-validator-provider} execution 移除 Hibernate Validator 後執行；
 * 一般的 test execution 會略過這個類別。
 */
class OsuIrcWithoutValidatorProviderTest {

    private static final String PROVIDER = "org.hibernate.validator.HibernateValidator";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OsuIrcAutoConfiguration.class))
            .withPropertyValues("osu.irc.auto-start=false");

    @BeforeAll
    static void requireClasspathWithoutProvider() {
        ClassLoader classLoader = OsuIrcWithoutValidatorProviderTest.class.getClassLoader();
        boolean providerPresent = ClassUtils.isPresent(PROVIDER, classLoader);
        if (Boolean.getBoolean("bancho.test.without-validator-provider")) {
            assertThat(providerPresent).as("surefire 應已從 classpath 移除 Hibernate Validator").isFalse();
            assertThat(ClassUtils.isPresent("jakarta.validation.Validation", classLoader)).isTrue();
        } else {
            assumeFalse(providerPresent, "只在 surefire 的 without-validator-provider execution 執行");
        }
    }

    @Test
    void startsWithCredentials() {
        runner.withPropertyValues("osu.irc.username=someone", "osu.irc.password=secret")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(OsuIrcClient.class));
    }

    @Test
    void rejectsBlankCredentials() {
        runner.withPropertyValues("osu.irc.username=", "osu.irc.password=")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("username"));
    }
}
