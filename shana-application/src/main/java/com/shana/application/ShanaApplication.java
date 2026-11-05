package com.shana.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用启动类。
 *
 * <p>只有本模块是可执行 jar，其余模块都是被装配的库模块。
 * <p>{@code scanBasePackages} 覆盖 {@code com.shana}，从而扫描到各业务模块的 Bean；
 * 否则默认只扫描本包 {@code com.shana.application}。
 * <p>{@code @EnableAsync} 为长期记忆摘要这类"不该阻塞请求"的任务提供线程池代理。
 * <p>{@code @EnableScheduling} 供月度报表批量任务使用；不开这个注解，
 * {@code @Scheduled} 注解只是装饰，不会报错也不会执行。
 */
@SpringBootApplication(scanBasePackages = "com.shana")
@ConfigurationPropertiesScan("com.shana")
@EnableAsync
@EnableScheduling
public class ShanaApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShanaApplication.class, args);
    }
}
