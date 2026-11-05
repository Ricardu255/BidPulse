package com.bidpulse.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * 分层约定用可执行规则固化，避免「这次先这样」慢慢侵蚀结构。
 *
 * <p>包名一律写全 {@code com.bidpulse.} 前缀：ArchUnit 的 {@code ..admin..} 这类模式
 * 也会匹配 {@code org.apache.kafka.clients.admin} 等第三方包，曾造成误报。
 *
 * <p>只扫描 main 代码（{@link ImportOption.DoNotIncludeTests}）：测试可以为了构造场景
 * 跨层引用，生产代码不行。
 */
@AnalyzeClasses(packages = "com.bidpulse", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule controllers_live_in_api_packages =
            classes().that().areAnnotatedWith(RestController.class)
                    .should().resideInAnyPackage("com.bidpulse.api..", "com.bidpulse.console.api..")
                    .because("HTTP 入口集中在 api 包，鉴权、异常处理、CORS 才能按包统一扫描");

    @ArchTest
    static final ArchRule no_field_injection =
            noFields().should().beAnnotatedWith(Autowired.class)
                    .because("统一构造器注入：依赖不可变，单测不需要反射塞字段");

    @ArchTest
    static final ArchRule hot_path_is_independent_from_console_and_identity =
            noClasses().that()
                    .resideInAnyPackage("com.bidpulse.bid..", "com.bidpulse.event..",
                            "com.bidpulse.breaker..", "com.bidpulse.job..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.bidpulse.console..", "com.bidpulse.admin..",
                            "com.bidpulse.auth..", "com.bidpulse.audit..")
                    .because("竞价热路径不能为了控制台或身份功能增加依赖与初始化成本");

    @ArchTest
    static final ArchRule infrastructure_is_independent_from_console_and_identity =
            noClasses().that().resideInAnyPackage("com.bidpulse.infra..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.bidpulse.console..", "com.bidpulse.admin..",
                            "com.bidpulse.auth..")
                    .because("基础设施层被热路径复用，不能反向依赖控制台或身份模块");

    @ArchTest
    static final ArchRule console_query_does_not_depend_on_command =
            noClasses().that().resideInAnyPackage("com.bidpulse.console.query..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.bidpulse.console.command..")
                    .because("只读查询不应触发写操作");

    @ArchTest
    static final ArchRule console_command_does_not_depend_on_query =
            noClasses().that().resideInAnyPackage("com.bidpulse.console.command..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.bidpulse.console.query..")
                    .because("写操作与只读查询各自演进，避免形成环");

    @ArchTest
    static final ArchRule api_dtos_do_not_depend_on_services_or_persistence =
            noClasses().that().resideInAnyPackage("com.bidpulse.api.dto..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.bidpulse.bid..", "com.bidpulse.admin..",
                            "com.bidpulse.infra..", "com.bidpulse.console..")
                    .because("DTO 只描述协议，不把服务/持久化类型泄漏给调用方");

    @ArchTest
    static final ArchRule console_controllers_do_not_touch_persistence =
            noClasses().that().resideInAnyPackage("com.bidpulse.console.api..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework.jdbc..", "com.bidpulse.infra.persistence..")
                    .because("SQL 属于 query/command 层，controller 只处理 HTTP 契约");

    @ArchTest
    static final ArchRule event_and_entity_are_framework_free =
            noClasses().that().resideInAnyPackage("com.bidpulse.event..", "com.bidpulse.entity..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework..", "org.apache.kafka..", "com.fasterxml.jackson..")
                    .because("领域载荷保持纯 Java，序列化与框架适配放在边界层");

    @ArchTest
    static final ArchRule only_console_depends_on_console =
            noClasses().that().resideOutsideOfPackage("com.bidpulse.console..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.bidpulse.console..")
                    .because("控制台是旁路功能，业务/基础设施代码不能反向依赖");

    @ArchTest
    static final ArchRule controllers_are_not_depended_upon =
            noClasses().that()
                    .resideOutsideOfPackages("com.bidpulse.api.controller..", "com.bidpulse.console.api..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.bidpulse.api.controller..", "com.bidpulse.console.api..")
                    .because("controller 只是入口，不应被服务或基础设施反向引用");
}
