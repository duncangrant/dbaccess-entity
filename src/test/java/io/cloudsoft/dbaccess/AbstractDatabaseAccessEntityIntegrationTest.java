package io.cloudsoft.dbaccess;


import java.net.InetAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.core.sensor.DependentConfiguration;
import org.apache.brooklyn.core.test.BrooklynAppLiveTestSupport;
import org.apache.brooklyn.entity.database.DatastoreMixins;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;

import com.google.common.collect.ImmutableList;

public abstract class AbstractDatabaseAccessEntityIntegrationTest extends BrooklynAppLiveTestSupport {
    protected static final String TEST_DATABASE = "testdatabase";
    protected static final String TEST_USERNAME = "testusername";
    protected static final String TEST_PASSWORD = "testpassword";
    protected static final String TEST_ADMIN_PASSWORD = "testadminpassword";
    protected Location testLocation;
    protected Connection connect = null;
    protected Statement statement = null;
    protected Entity databaseNode;

    private static final Logger LOG = LoggerFactory.getLogger(AbstractDatabaseAccessEntityIntegrationTest.class);

    @BeforeMethod
    @Override
    public void setUp() throws Exception {
        super.setUp();
        testLocation = app.newLocalhostProvisioningLocation();
    }

    protected void testAccess(Entity entity) throws Exception {
        String database = entity.config().get(DatabaseAccessEntity.DATABASE);
        String username = entity.sensors().get(DatabaseAccessEntity.USERNAME);
        String password = entity.sensors().get(DatabaseAccessEntity.PASSWORD);
        String url = entity.sensors().get(DatabaseAccessEntity.DATASTORE_URL);
        String endpoint = entity.config().get(DatabaseAccessEntity.ENDPOINT_URL);
        Assert.assertEquals(url, String.format("%s%s?user=%s&password=%s", endpoint, database, username, password));
        connect(url);
        ResultSet results = statement.executeQuery(getDatabaseNamesStatement());
        results.last();
        Assert.assertEquals(results.getRow(), 1);
        String dbName = results.getString(1);
        Assert.assertEquals(dbName, TEST_DATABASE);
    }

    public void connect(String url) throws Exception {
        try {
            Class.forName(getDriverName());
            // For MySQL, the endpoint of the node is in the form "mysql://hostname:3306/", however we are only allowing
            // login from <user>@localhost and <user>@*, so swap out the hostname in the endpoint for 'localhost'
            String localhostUrl = Strings.replaceAll(url, InetAddress.getLocalHost().getHostName(), "localhost");
            String jdbcUrl = String.format("jdbc:%s", localhostUrl);
            LOG.info("Connecting to " + jdbcUrl);
            connect = DriverManager.getConnection(jdbcUrl);
            statement = connect.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
        } catch (Exception ex) {
            close();
            throw ex;
        }
    }

    public void close() throws Exception {
        if (statement != null) {
            statement.close();
            statement = null;
        }

        if (connect != null) {
            connect.close();
            connect = null;
        }
    }

    protected <T extends DatabaseAccessEntity> T createDatabaseAccessEntity(EntitySpec<T> spec){
        app.start(ImmutableList.of(testLocation));
        spec.configure(DatabaseAccessEntity.DATABASE, TEST_DATABASE);
        spec.configure(DatabaseAccessEntity.ADMIN_USER, getAdminUserName());
        spec.configure(DatabaseAccessEntity.ADMIN_PASSWORD, TEST_ADMIN_PASSWORD);
        spec.configure(DatabaseAccessEntity.ENDPOINT_URL, DependentConfiguration.attributeWhenReady(databaseNode, DatastoreMixins.DATASTORE_URL));
        T entity = app.createAndManageChild(spec);
        return entity;
    }

    protected void runTest(DatabaseAccessEntity entity) throws Exception {
        testAccess(entity);
        entity.stop();
        try {
            testAccess(entity);
            Assert.fail("able to access database after entity stopped, expected authentication failure");
        } catch (SQLException e) {
            String message = getExpectedAccessDeniedMessage(entity.getAttribute(DatabaseAccessEntity.USERNAME));
            Assert.assertTrue(e.getMessage().contains(message),
                    "Expected \"" + message + "...\" exception, got: " + e.getMessage());
        }
    }

    protected abstract String getDatabaseNamesStatement();

    protected abstract String getDriverName();

    protected abstract String getAdminUserName();

    protected abstract String getExpectedAccessDeniedMessage(String username);
}
