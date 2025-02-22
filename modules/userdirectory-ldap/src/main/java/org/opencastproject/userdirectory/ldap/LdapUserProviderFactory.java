/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.userdirectory.ldap;

import org.opencastproject.security.api.Organization;
import org.opencastproject.security.api.OrganizationDirectoryService;
import org.opencastproject.security.api.SecurityService;
import org.opencastproject.security.api.UserProvider;
import org.opencastproject.userdirectory.JpaGroupRoleProvider;
import org.opencastproject.util.NotFoundException;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedServiceFactory;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.ldap.userdetails.LdapAuthoritiesPopulator;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

/**
 * LDAP implementation of the spring UserDetailsService, taking configuration information from the component context.
 */
@Component(
    immediate = true,
    service = ManagedServiceFactory.class,
    property = {
        "service.pid=org.opencastproject.userdirectory.ldap",
        "service.description=Provides ldap user directory instances"
    }
)
public class LdapUserProviderFactory implements ManagedServiceFactory {

  /** The logger */
  private static final Logger logger = LoggerFactory.getLogger(LdapUserProviderFactory.class);

  /** This service factory's PID */
  private static final String PID = "org.opencastproject.userdirectory.ldap";

  /** The key to look up the ldap search filter in the service configuration properties */
  private static final String SEARCH_FILTER_KEY = "org.opencastproject.userdirectory.ldap.searchfilter";

  /** The key to look up the ldap search base in the service configuration properties */
  private static final String SEARCH_BASE_KEY = "org.opencastproject.userdirectory.ldap.searchbase";

  /** The key to look up the ldap server URL in the service configuration properties */
  private static final String LDAP_URL_KEY = "org.opencastproject.userdirectory.ldap.url";

  /** The key to look up the role attributes in the service configuration properties */
  private static final String ROLE_ATTRIBUTES_KEY = "org.opencastproject.userdirectory.ldap.roleattributes";

  /** The key to look up the organization identifier in the service configuration properties */
  private static final String ORGANIZATION_KEY = "org.opencastproject.userdirectory.ldap.org";

  /** The key to look up the user DN to use for performing searches. */
  private static final String SEARCH_USER_DN = "org.opencastproject.userdirectory.ldap.userDn";

  /** The key to look up the password to use for performing searches */
  private static final String SEARCH_PASSWORD = "org.opencastproject.userdirectory.ldap.password";

  /** The key to look up the number of user records to cache */
  private static final String CACHE_SIZE = "org.opencastproject.userdirectory.ldap.cache.size";

  /** The key to look up the number of minutes to cache users */
  private static final String CACHE_EXPIRATION = "org.opencastproject.userdirectory.ldap.cache.expiration";

  /** The key to indicate a prefix that will be added to every role read from the LDAP */
  private static final String ROLE_PREFIX_KEY = "org.opencastproject.userdirectory.ldap.roleprefix";

  /**
   * The key to indicate a comma-separated list of prefixes.
   * The "role prefix" defined with the ROLE_PREFIX_KEY will not be prepended to the roles starting with any of these
   */
  private static final String EXCLUDE_PREFIXES_KEY = "org.opencastproject.userdirectory.ldap.exclude.prefixes";

  /**
   * The key to indicate a prefix,
   * which is used to check whether a roleattribute value shall be added as a group to the user
   */
  private static final String GROUP_CHECK_PREFIX_KEY = "org.opencastproject.userdirectory.ldap.groupcheckprefix";

  /** Specifies, whether the roleattributes should be added as a role */
  private static final String APPLY_ROLEATTRIBUTES_AS_ROLES_KEY
      = "org.opencastproject.userdirectory.ldap.roleattributes.applyasroles";

  /** Specifies, whether the roleattributes should be added as a group */
  private static final String APPLY_ROLEATTRIBUTES_AS_GROUPS_KEY
      = "org.opencastproject.userdirectory.ldap.roleattributes.applyasgroups";

  /** The prefix of the keys, which map a ldap attribute to opencast roles */
  private static final String ATTRIBUTE_MAPPING_KEY_PREFIX = "org.opencastproject.userdirectory.ldap.map.";

  /** The postfix of the attribute maps, which specifiy the value to map */
  private static final String ATTRIBUTE_MAPPING_KEY_POSTFIX_VALUE = "value";

  /** The postfix of the attribute maps, which map a ldap attribute to opencast roles */
  private static final String ATTRIBUTE_MAPPING_KEY_POSTFIX_ROLES = "roles";

  /** The postfix of the attribute maps, which map a ldap attribute to opencast groups */
  private static final String ATTRIBUTE_MAPPING_KEY_POSTFIX_GROUPS = "groups";

  /** The key to indicate whether or not the roles should be converted to uppercase */
  private static final String UPPERCASE_KEY = "org.opencastproject.userdirectory.ldap.uppercase";

  /** The key to indicate a unique identifier for each LDAP connection */
  private static final String INSTANCE_ID_KEY = "org.opencastproject.userdirectory.ldap.id";

  /** The key to indicate a comma-separated list of extra roles to add to the authenticated user */
  private static final String EXTRA_ROLES_KEY = "org.opencastproject.userdirectory.ldap.extra.roles";

  /** The key to setup a LDAP connection ID as an OSGI service property */
  private static final String INSTANCE_ID_SERVICE_PROPERTY_KEY = "instanceId";

  /** A map of pid to ldap user provider instance */
  private Map<String, ServiceRegistration> providerRegistrations = new ConcurrentHashMap<>();

  /** A map of pid to ldap authorities populator instance */
  private Map<String, ServiceRegistration> authoritiesPopulatorRegistrations = new ConcurrentHashMap<>();

  /** The OSGI bundle context */
  private BundleContext bundleContext = null;

  /** The organization directory service */
  private OrganizationDirectoryService orgDirectory;

  /** The group role provider service */
  private JpaGroupRoleProvider groupRoleProvider;

  /** A reference to Opencast's security service */
  private SecurityService securityService;

  /** OSGi callback for setting the organization directory service. */
  @Reference
  public void setOrgDirectory(OrganizationDirectoryService orgDirectory) {
    this.orgDirectory = orgDirectory;
  }

  /** OSGi callback for setting the role group service. */
  @Reference
  public void setGroupRoleProvider(JpaGroupRoleProvider groupRoleProvider) {
    this.groupRoleProvider = groupRoleProvider;
  }

  /** OSGi callback for setting the security service. */
  @Reference
  public void setSecurityService(SecurityService securityService) {
    this.securityService = securityService;
  }

  /**
   * Callback for activation of this component.
   *
   * @param cc
   *          the component context
   */
  @Activate
  public void activate(ComponentContext cc) {
    logger.debug("Activate LdapUserProviderFactory");
    bundleContext = cc.getBundleContext();
  }

  /**
   * {@inheritDoc}
   *
   * @see org.osgi.service.cm.ManagedServiceFactory#getName()
   */
  @Override
  public String getName() {
    return PID;
  }

  /**
   * Retrieve configuration values and check for a proper value.
   *
   * @param properties
   *      Configuration dictionary
   * @param key
   *      Configuration key to check for
   * @return
   *      The configuration value
   * @throws ConfigurationException
   *      Thrown if the configuration value is blank
   */
  private String getRequiredProperty(final Dictionary properties, final String key) throws ConfigurationException {
    final String value = (String) properties.get(key);
    if (StringUtils.isBlank(value)) {
      throw new ConfigurationException(key, "missing configuration value");
    }
    return value;
  }

  /**
   * {@inheritDoc}
   *
   * @see org.osgi.service.cm.ManagedServiceFactory#updated(java.lang.String, java.util.Dictionary)
   */
  @Override
  public void updated(String pid, Dictionary properties) throws ConfigurationException {
    logger.debug("Updating LdapUserProviderFactory");

    // required settings
    String searchBase = getRequiredProperty(properties, SEARCH_BASE_KEY);
    String searchFilter = getRequiredProperty(properties, SEARCH_FILTER_KEY);
    String url = getRequiredProperty(properties, LDAP_URL_KEY);
    String instanceId = getRequiredProperty(properties, INSTANCE_ID_KEY);
    String roleAttributes = getRequiredProperty(properties, ROLE_ATTRIBUTES_KEY);

    // optional settings
    String organization = (String) properties.get(ORGANIZATION_KEY);
    String userDn = (String) properties.get(SEARCH_USER_DN);
    String password = (String) properties.get(SEARCH_PASSWORD);

    // optional with default values
    String rolePrefix = Objects.toString(properties.get(ROLE_PREFIX_KEY), "ROLE_");
    String[] excludePrefixes = StringUtils.split((String) properties.get(EXCLUDE_PREFIXES_KEY), ",");
    String groupCheckPrefix = Objects.toString(properties.get(GROUP_CHECK_PREFIX_KEY), "ROLE_GROUP_");
    boolean convertToUppercase = BooleanUtils.toBoolean(Objects.toString(properties.get(UPPERCASE_KEY), "true"));
    int cacheSize = NumberUtils.toInt((String) properties.get(CACHE_SIZE), 1000);
    int cacheExpiration = NumberUtils.toInt((String) properties.get(CACHE_EXPIRATION), 5);
    boolean applyRoleattributesAsRoles = BooleanUtils.toBoolean(Objects.toString(
            properties.get(APPLY_ROLEATTRIBUTES_AS_ROLES_KEY), "true"));
    boolean applyRoleattributesAsGroups = BooleanUtils.toBoolean(Objects.toString(
            properties.get(APPLY_ROLEATTRIBUTES_AS_GROUPS_KEY), "true"));
    if (applyRoleattributesAsGroups && !applyRoleattributesAsRoles) {
      throw new ConfigurationException(APPLY_ROLEATTRIBUTES_AS_GROUPS_KEY,
              "'" + APPLY_ROLEATTRIBUTES_AS_ROLES_KEY + "' needs to be 'true' to enable this option");
    }

    // extra roles
    String[] extraRoles =  StringUtils.split(Objects.toString(properties.get(EXTRA_ROLES_KEY), ""), ",");
    Set<String> extraRoleSet = new HashSet<>(Arrays.asList(extraRoles));
    extraRoleSet.addAll(Arrays.asList("ROLE_ANONYMOUS", "ROLE_USER"));
    extraRoles = extraRoleSet.toArray(new String[extraRoles.length]);

    // maps
    HashMap<String, HashMap<String, String>> ldapAssignmentMappingsPreparation = new HashMap();
    for (Enumeration<String> e = properties.keys(); e.hasMoreElements();) {
      String key = e.nextElement();

      if (key.startsWith(ATTRIBUTE_MAPPING_KEY_PREFIX)) {
        final String[] postfix = key.substring(ATTRIBUTE_MAPPING_KEY_PREFIX.length()).split("\\.");

        if (postfix.length != 2) {
          throw new ConfigurationException(key,
                  "Invalid Configkey format, the following format is needed: "
                  + ATTRIBUTE_MAPPING_KEY_PREFIX + "<identifier>.<key>");
        }

        final String mappingIdentifier = postfix[0];
        final String mappingKey = postfix[1];

        HashMap keyValueMap = ldapAssignmentMappingsPreparation.getOrDefault(mappingIdentifier, new HashMap());

        keyValueMap.put(mappingKey, (String) properties.get(key));

        ldapAssignmentMappingsPreparation.put(mappingIdentifier, keyValueMap);
      }
    }
    HashMap<String, String[]> ldapAssignmentRoleMap = new HashMap();
    HashMap<String, String[]> ldapAssignmentGroupMap = new HashMap();
    for (HashMap.Entry<String, HashMap<String, String>> entry : ldapAssignmentMappingsPreparation.entrySet()) {
      HashMap<String, String> mappingConf = entry.getValue();
      String value = StringUtils.trimToNull(mappingConf.get(ATTRIBUTE_MAPPING_KEY_POSTFIX_VALUE));
      String roles = StringUtils.trimToNull(mappingConf.get(ATTRIBUTE_MAPPING_KEY_POSTFIX_ROLES));
      String groups = StringUtils.trimToNull(mappingConf.get(ATTRIBUTE_MAPPING_KEY_POSTFIX_GROUPS));

      if (value == null) {
        throw new ConfigurationException(ATTRIBUTE_MAPPING_KEY_PREFIX + entry.getKey() + ".*",
                "LDAP mapping incomplete, the key 'value' is needed");
      }
      if (roles == null && groups == null) {
        throw new ConfigurationException(ATTRIBUTE_MAPPING_KEY_PREFIX + entry.getKey() + ".*",
                "LDAP mapping incomplete, one of the keys 'roles' or 'groups' is needed");
      }

      if (convertToUppercase) {
        value = value.toUpperCase();
      }

      if (roles != null) {
        if (convertToUppercase) {
          roles = roles.toUpperCase();
        }
        ldapAssignmentRoleMap.put(value,
                ArrayUtils.addAll(
                        ldapAssignmentRoleMap.getOrDefault(value, new String[0]),
                        Arrays.stream(roles.split(","))
                                .map(r -> StringUtils.trimToNull(r))
                                .filter(r -> r != null)
                                .toArray(String[]::new)
                )
        );
      }

      if (groups != null) {
        if (convertToUppercase) {
          groups = groups.toUpperCase();
        }
        ldapAssignmentGroupMap.put(value,
                ArrayUtils.addAll(
                        ldapAssignmentGroupMap.getOrDefault(value, new String[0]),
                        Arrays.stream(groups.split(","))
                                .map(r -> StringUtils.trimToNull(r))
                                .filter(r -> r != null)
                                .toArray(String[]::new)
                )
        );
      }
    }

    // Now that we have everything we need, go ahead and activate a new provider, removing an old one if necessary
    ServiceRegistration existingRegistration = providerRegistrations.remove(pid);
    if (existingRegistration != null) {
      existingRegistration.unregister();
    }

    // Defaults to first available organization
    Organization org;
    try {
      if (StringUtils.isNoneBlank(organization)) {
        org = orgDirectory.getOrganization(organization);
      } else {
        if (orgDirectory.getOrganizations().size() != 1) {
          throw new NotFoundException("Multiple organizations exist but none is specified");
        }
        org = orgDirectory.getOrganizations().get(0);
      }
    } catch (NotFoundException e) {
      throw new ConfigurationException(ORGANIZATION_KEY, "no organization with configured id", e);
    }

    // Dictionary to include a property to identify this LDAP instance in the security.xml file
    Hashtable<String, String> dict = new Hashtable<>();
    dict.put(INSTANCE_ID_SERVICE_PROPERTY_KEY, instanceId);

    // Instantiate this LDAP instance and register it as such
    LdapUserProviderInstance provider = new LdapUserProviderInstance(pid, org, searchBase, searchFilter, url, userDn,
            password, roleAttributes, rolePrefix, extraRoles, excludePrefixes, convertToUppercase, cacheSize,
            cacheExpiration, securityService);

    providerRegistrations.put(pid, bundleContext.registerService(UserProvider.class.getName(), provider, null));

    OpencastLdapAuthoritiesPopulator authoritiesPopulator = new OpencastLdapAuthoritiesPopulator(roleAttributes,
            rolePrefix, excludePrefixes, groupCheckPrefix, applyRoleattributesAsRoles, applyRoleattributesAsGroups,
            ldapAssignmentRoleMap, ldapAssignmentGroupMap, convertToUppercase, org, securityService,
            groupRoleProvider, extraRoles);

    // Also, register this instance as LdapAuthoritiesPopulator so that it can be used within the security.xml file
    authoritiesPopulatorRegistrations.put(pid,
            bundleContext.registerService(LdapAuthoritiesPopulator.class.getName(), authoritiesPopulator, dict));
  }

  /**
   * {@inheritDoc}
   *
   * @see org.osgi.service.cm.ManagedServiceFactory#deleted(java.lang.String)
   */
  @Override
  public void deleted(String pid) {
    ServiceRegistration providerRegistration = null;
    ServiceRegistration authoritiesPopulatorRegistration = null;

    try {
      providerRegistration = providerRegistrations.remove(pid);
      authoritiesPopulatorRegistration = authoritiesPopulatorRegistrations.remove(pid);
      if ((providerRegistration != null) || (authoritiesPopulatorRegistration != null)) {
        try {
          ManagementFactory.getPlatformMBeanServer().unregisterMBean(LdapUserProviderFactory.getObjectName(pid));
        } catch (Exception e) {
          logger.warn("Unable to unregister mbean for pid='{}': {}", pid, e.getMessage());
        }
      }
    } finally {
      if (providerRegistration != null) {
        providerRegistration.unregister();
      }
      if (authoritiesPopulatorRegistration != null) {
        authoritiesPopulatorRegistration.unregister();
      }
    }
  }

  /**
   * Builds a JMX object name for a given PID
   *
   * @param pid
   *          the PID
   * @return the object name
   * @throws NullPointerException
   * @throws MalformedObjectNameException
   */
  public static final ObjectName getObjectName(String pid) throws MalformedObjectNameException, NullPointerException {
    return new ObjectName(pid + ":type=LDAPRequests");
  }

}
