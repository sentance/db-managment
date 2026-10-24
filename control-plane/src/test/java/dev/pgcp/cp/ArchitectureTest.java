package dev.pgcp.cp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import dev.pgcp.cp.pg.InstanceConnector;

class ArchitectureTest {

    static final JavaClasses CLASSES = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.pgcp.cp");

    /** DDL against user clusters lives in one reviewed place, where every identifier is quoted. */
    @Test
    void onlyThePgLayerExecutesRawSql() {
        noClasses().that().resideOutsideOfPackage("..cp.pg..")
                .should().callMethod(Statement.class, "execute", String.class)
                .check(CLASSES);
    }

    @Test
    void onlyTheConnectorOpensClusterConnections() {
        noClasses().that().doNotHaveFullyQualifiedName(InstanceConnector.class.getName())
                .should().callMethod(DriverManager.class, "getConnection", String.class, Properties.class)
                .orShould().callMethod(DriverManager.class, "getConnection", String.class, String.class, String.class)
                .check(CLASSES);
    }

    /** The control plane never talks to Docker; only the agent does. */
    @Test
    void controlPlaneHasNoDockerAccess() {
        noClasses().should().dependOnClassesThat().resideInAPackage("com.github.dockerjava..").check(CLASSES);
    }
}
