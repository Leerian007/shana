package com.shana.application;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 应用启动类。
 *
 * <p>只有本模块是可执行 jar，其余模块都是被装配的库模块。
 * <p>{@code scanBasePackages} 覆盖 {@code com.shana}，从而扫描到各业务模块的 Bean；
 * 否则默认只扫描本包 {@code com.shana.application}。
 */
@SpringBootApplication(scanBasePackages = "com.shana")
@ConfigurationPropertiesScan("com.shana")
public class ShanaApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShanaApplication.class, args);
    }
}
