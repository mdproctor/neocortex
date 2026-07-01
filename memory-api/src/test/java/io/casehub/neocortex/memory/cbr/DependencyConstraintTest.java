package io.casehub.neocortex.memory.cbr;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "io.casehub.neocortex.memory.cbr")
class DependencyConstraintTest {

    @ArchTest
    static final ArchRule noQuarkus = noClasses().should()
        .dependOnClassesThat().resideInAnyPackage("io.quarkus..", "jakarta..");

    @ArchTest
    static final ArchRule noLangChain4j = noClasses().should()
        .dependOnClassesThat().resideInAnyPackage("dev.langchain4j..");

    @ArchTest
    static final ArchRule noQdrant = noClasses().should()
        .dependOnClassesThat().resideInAnyPackage("io.qdrant..");

    @ArchTest
    static final ArchRule onlyAllowedCasehubDeps = noClasses()
        .that().resideInAPackage("io.casehub.neocortex.memory.cbr..")
        .should().dependOnClassesThat(
            DescribedPredicate.describe("casehub classes outside platform-api and memory-cbr",
                (JavaClass cls) -> cls.getPackageName().startsWith("io.casehub.")
                    && !cls.getPackageName().startsWith("io.casehub.neocortex.memory.cbr")
                    && !cls.getPackageName().startsWith("io.casehub.neocortex.memory")));
}
