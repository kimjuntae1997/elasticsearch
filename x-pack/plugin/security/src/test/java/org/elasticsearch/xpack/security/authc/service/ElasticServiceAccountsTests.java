/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.security.authc.service;

import org.elasticsearch.action.admin.cluster.health.ClusterHealthAction;
import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsAction;
import org.elasticsearch.action.admin.indices.create.AutoCreateAction;
import org.elasticsearch.action.admin.indices.create.CreateIndexAction;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexAction;
import org.elasticsearch.action.admin.indices.mapping.put.AutoPutMappingAction;
import org.elasticsearch.action.admin.indices.refresh.RefreshAction;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsAction;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsAction;
import org.elasticsearch.action.admin.indices.template.delete.DeleteIndexTemplateAction;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesAction;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateAction;
import org.elasticsearch.action.bulk.BulkAction;
import org.elasticsearch.action.delete.DeleteAction;
import org.elasticsearch.action.get.GetAction;
import org.elasticsearch.action.get.MultiGetAction;
import org.elasticsearch.action.index.IndexAction;
import org.elasticsearch.action.search.MultiSearchAction;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.cluster.metadata.IndexAbstraction;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.xpack.core.ilm.action.GetLifecycleAction;
import org.elasticsearch.xpack.core.ilm.action.PutLifecycleAction;
import org.elasticsearch.xpack.core.monitoring.action.MonitoringBulkAction;
import org.elasticsearch.xpack.core.security.action.apikey.CreateApiKeyAction;
import org.elasticsearch.xpack.core.security.action.apikey.CreateApiKeyRequest;
import org.elasticsearch.xpack.core.security.action.apikey.GetApiKeyAction;
import org.elasticsearch.xpack.core.security.action.apikey.GetApiKeyRequest;
import org.elasticsearch.xpack.core.security.action.apikey.InvalidateApiKeyAction;
import org.elasticsearch.xpack.core.security.action.apikey.InvalidateApiKeyRequest;
import org.elasticsearch.xpack.core.security.action.role.PutRoleAction;
import org.elasticsearch.xpack.core.security.action.user.PutUserAction;
import org.elasticsearch.xpack.core.security.authc.Authentication;
import org.elasticsearch.xpack.core.security.authc.AuthenticationTestHelper;
import org.elasticsearch.xpack.core.security.authz.RoleDescriptor;
import org.elasticsearch.xpack.core.security.authz.permission.FieldPermissionsCache;
import org.elasticsearch.xpack.core.security.authz.permission.Role;
import org.elasticsearch.xpack.core.security.authz.privilege.ApplicationPrivilegeDescriptor;
import org.elasticsearch.xpack.core.security.authz.privilege.ApplicationPrivilegeTests;
import org.elasticsearch.xpack.core.security.authz.store.ReservedRolesStore;
import org.elasticsearch.xpack.core.security.user.KibanaSystemUser;
import org.elasticsearch.xpack.core.security.user.User;
import org.elasticsearch.xpack.security.authc.service.ElasticServiceAccounts.ElasticServiceAccount;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.xpack.core.security.test.TestRestrictedIndices.RESTRICTED_INDICES;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ElasticServiceAccountsTests extends ESTestCase {

    public void testKibanaSystemPrivileges() {
        final RoleDescriptor serviceAccountRoleDescriptor = ElasticServiceAccounts.ACCOUNTS.get("elastic/kibana").roleDescriptor();
        final RoleDescriptor reservedRolesStoreRoleDescriptor = ReservedRolesStore.kibanaSystemRoleDescriptor(KibanaSystemUser.ROLE_NAME);
        assertThat(serviceAccountRoleDescriptor.getClusterPrivileges(), equalTo(reservedRolesStoreRoleDescriptor.getClusterPrivileges()));
        assertThat(
            serviceAccountRoleDescriptor.getApplicationPrivileges(),
            equalTo(reservedRolesStoreRoleDescriptor.getApplicationPrivileges())
        );
        assertThat(serviceAccountRoleDescriptor.getIndicesPrivileges(), equalTo(reservedRolesStoreRoleDescriptor.getIndicesPrivileges()));
        assertThat(
            serviceAccountRoleDescriptor.getConditionalClusterPrivileges(),
            equalTo(reservedRolesStoreRoleDescriptor.getConditionalClusterPrivileges())
        );
        assertThat(serviceAccountRoleDescriptor.getRunAs(), equalTo(reservedRolesStoreRoleDescriptor.getRunAs()));
        assertThat(serviceAccountRoleDescriptor.getMetadata(), equalTo(reservedRolesStoreRoleDescriptor.getMetadata()));
    }

    public void testElasticFleetServerPrivileges() {
        final String allowedApplicationActionPattern = "example/custom/action/*";
        final String kibanaApplication = "kibana-" + randomFrom(randomAlphaOfLengthBetween(8, 24), ".kibana");
        final Role role = Role.buildFromRoleDescriptor(
            ElasticServiceAccounts.ACCOUNTS.get("elastic/fleet-server").roleDescriptor(),
            new FieldPermissionsCache(Settings.EMPTY),
            RESTRICTED_INDICES,
            List.of(
                new ApplicationPrivilegeDescriptor(
                    kibanaApplication,
                    "reserved_fleet-setup",
                    Set.of(allowedApplicationActionPattern),
                    Map.of()
                )
            )
        );
        final Authentication authentication = AuthenticationTestHelper.builder().serviceAccount().build();
        assertThat(
            role.cluster()
                .check(CreateApiKeyAction.NAME, new CreateApiKeyRequest(randomAlphaOfLengthBetween(3, 8), null, null), authentication),
            is(true)
        );
        assertThat(
            role.cluster().check(GetApiKeyAction.NAME, GetApiKeyRequest.builder().ownedByAuthenticatedUser().build(), authentication),
            is(true)
        );
        assertThat(role.cluster().check(InvalidateApiKeyAction.NAME, InvalidateApiKeyRequest.forOwnedApiKeys(), authentication), is(true));

        assertThat(role.cluster().check(GetApiKeyAction.NAME, randomFrom(GetApiKeyRequest.builder().build()), authentication), is(false));
        assertThat(
            role.cluster()
                .check(
                    InvalidateApiKeyAction.NAME,
                    InvalidateApiKeyRequest.usingUserName(randomAlphaOfLengthBetween(3, 16)),
                    authentication
                ),
            is(false)
        );

        List.of(
            "logs-" + randomAlphaOfLengthBetween(1, 20),
            "metrics-" + randomAlphaOfLengthBetween(1, 20),
            "traces-" + randomAlphaOfLengthBetween(1, 20),
            ".logs-endpoint.diagnostic.collection-" + randomAlphaOfLengthBetween(1, 20),
            ".logs-endpoint.action.responses-" + randomAlphaOfLengthBetween(1, 20),
            ".logs-endpoint.heartbeat-" + randomAlphaOfLengthBetween(1, 20)
        ).stream().map(this::mockIndexAbstraction).forEach(index -> {
            assertThat(role.indices().allowedIndicesMatcher(AutoPutMappingAction.NAME).test(index), is(true));
            assertThat(role.indices().allowedIndicesMatcher(AutoCreateAction.NAME).test(index), is(true));
            assertThat(role.indices().allowedIndicesMatcher(DeleteAction.NAME).test(index), is(true));
            assertThat(role.indices().allowedIndicesMatcher(CreateIndexAction.NAME).test(index), is(true));
            assertThat(role.indices().allowedIndicesMatcher(IndexAction.NAME).test(index), is(true));
            assertThat(role.indices().allowedIndicesMatcher(BulkAction.NAME).test(index), is(true));
            assertThat(role.indices().allowedIndicesMatcher(DeleteIndexAction.NAME).test(index), is(false));
            assertThat(role.indices().allowedIndicesMatcher(GetAction.NAME).test(index), is(false));
            assertThat(role.indices().allowedIndicesMatcher(MultiGetAction.NAME).test(index), is(false));
            assertThat(role.indices().allowedIndicesMatcher(SearchAction.NAME).test(index), is(false));
            assertThat(role.indices().allowedIndicesMatcher(MultiSearchAction.NAME).test(index), is(false));
            assertThat(role.indices().allowedIndicesMatcher(UpdateSettingsAction.NAME).test(index), is(false));
        });

        final IndexAbstraction profilingIndex = mockIndexAbstraction("profiling-" + randomAlphaOfLengthBetween(1, 20));
        assertThat(role.indices().allowedIndicesMatcher(AutoPutMappingAction.NAME).test(profilingIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(AutoCreateAction.NAME).test(profilingIndex), is(false));
        assertThat(role.indices().allowedIndicesMatcher(DeleteAction.NAME).test(profilingIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(CreateIndexAction.NAME).test(profilingIndex), is(false));
        assertThat(role.indices().allowedIndicesMatcher(IndexAction.NAME).test(profilingIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(BulkAction.NAME).test(profilingIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(DeleteIndexAction.NAME).test(profilingIndex), is(false));
        assertThat(role.indices().allowedIndicesMatcher(GetAction.NAME).test(profilingIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(MultiGetAction.NAME).test(profilingIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(SearchAction.NAME).test(profilingIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(MultiSearchAction.NAME).test(profilingIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(UpdateSettingsAction.NAME).test(profilingIndex), is(false));

        List.of("synthetics-" + randomAlphaOfLengthBetween(1, 20)).stream().map(this::mockIndexAbstraction).forEach(index -> {
            assertThat(role.indices().allowedIndicesMatcher(AutoPutMappingAction.NAME).test(index), is(true));
            assertThat(role.indices().allowedIndicesMatcher(AutoCreateAction.NAME).test(index), is(true));
            assertThat(role.indices().allowedIndicesMatcher(DeleteAction.NAME).test(index), is(true));
            assertThat(role.indices().allowedIndicesMatcher(CreateIndexAction.NAME).test(index), is(true));
            assertThat(role.indices().allowedIndicesMatcher(IndexAction.NAME).test(index), is(true));
            assertThat(role.indices().allowedIndicesMatcher(BulkAction.NAME).test(index), is(true));
            assertThat(role.indices().allowedIndicesMatcher(DeleteIndexAction.NAME).test(index), is(false));
            assertThat(role.indices().allowedIndicesMatcher(GetAction.NAME).test(index), is(true));
            assertThat(role.indices().allowedIndicesMatcher(MultiGetAction.NAME).test(index), is(true));
            assertThat(role.indices().allowedIndicesMatcher(SearchAction.NAME).test(index), is(true));
            assertThat(role.indices().allowedIndicesMatcher(MultiSearchAction.NAME).test(index), is(true));
            assertThat(role.indices().allowedIndicesMatcher(UpdateSettingsAction.NAME).test(index), is(false));
        });

        List.of(
            ".fleet-actions" + randomAlphaOfLengthBetween(1, 20),
            ".fleet-agents" + randomAlphaOfLengthBetween(1, 20),
            ".fleet-enrollment-api-keys" + randomAlphaOfLengthBetween(1, 20),
            ".fleet-policies" + randomAlphaOfLengthBetween(1, 20),
            ".fleet-policies-leader" + randomAlphaOfLengthBetween(1, 20),
            ".fleet-servers" + randomAlphaOfLengthBetween(1, 20),
            ".fleet-artifacts" + randomAlphaOfLengthBetween(1, 20),
            ".fleet-actions-results" + randomAlphaOfLengthBetween(1, 20),
            ".fleet-fileds" + randomAlphaOfLengthBetween(1, 20)
        ).forEach(index -> {
            final IndexAbstraction dotFleetIndex = mockIndexAbstraction(index);
            assertThat(role.indices().allowedIndicesMatcher(DeleteAction.NAME).test(dotFleetIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(CreateIndexAction.NAME).test(dotFleetIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(IndexAction.NAME).test(dotFleetIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(BulkAction.NAME).test(dotFleetIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(GetAction.NAME).test(dotFleetIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(MultiGetAction.NAME).test(dotFleetIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(SearchAction.NAME).test(dotFleetIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(MultiSearchAction.NAME).test(dotFleetIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(IndicesStatsAction.NAME).test(dotFleetIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(DeleteIndexAction.NAME).test(dotFleetIndex), is(false));
            assertThat(role.indices().allowedIndicesMatcher(UpdateSettingsAction.NAME).test(dotFleetIndex), is(false));
            assertThat(role.indices().allowedIndicesMatcher("indices:foo").test(dotFleetIndex), is(false));
        });

        final IndexAbstraction dotFleetSecretsIndex = mockIndexAbstraction(".fleet-secrets" + randomAlphaOfLengthBetween(1, 20));
        assertThat(role.indices().allowedIndicesMatcher(DeleteAction.NAME).test(dotFleetSecretsIndex), is(false));
        assertThat(role.indices().allowedIndicesMatcher(CreateIndexAction.NAME).test(dotFleetSecretsIndex), is(false));
        assertThat(role.indices().allowedIndicesMatcher(IndexAction.NAME).test(dotFleetSecretsIndex), is(false));
        assertThat(role.indices().allowedIndicesMatcher(BulkAction.NAME).test(dotFleetSecretsIndex), is(false));
        assertThat(role.indices().allowedIndicesMatcher(GetAction.NAME).test(dotFleetSecretsIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(MultiGetAction.NAME).test(dotFleetSecretsIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(SearchAction.NAME).test(dotFleetSecretsIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(MultiSearchAction.NAME).test(dotFleetSecretsIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(IndicesStatsAction.NAME).test(dotFleetSecretsIndex), is(false));
        assertThat(role.indices().allowedIndicesMatcher(DeleteIndexAction.NAME).test(dotFleetSecretsIndex), is(false));
        assertThat(role.indices().allowedIndicesMatcher(UpdateSettingsAction.NAME).test(dotFleetSecretsIndex), is(false));
        assertThat(role.indices().allowedIndicesMatcher("indices:foo").test(dotFleetSecretsIndex), is(false));

        final TransportRequest request = mock(TransportRequest.class);
        assertThat(role.cluster().check("cluster:admin/fleet/secrets/get", request, authentication), is(true));
        assertThat(role.cluster().check("cluster:admin/fleet/secrets/post", request, authentication), is(false));
        assertThat(role.cluster().check("cluster:admin/fleet/secrets/delete", request, authentication), is(false));

        final IndexAbstraction apmSampledTracesIndex = mockIndexAbstraction("traces-apm.sampled-" + randomAlphaOfLengthBetween(1, 20));
        assertThat(role.indices().allowedIndicesMatcher(DeleteAction.NAME).test(apmSampledTracesIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(CreateIndexAction.NAME).test(apmSampledTracesIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(IndexAction.NAME).test(apmSampledTracesIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(BulkAction.NAME).test(apmSampledTracesIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(GetAction.NAME).test(apmSampledTracesIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(MultiGetAction.NAME).test(apmSampledTracesIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(SearchAction.NAME).test(apmSampledTracesIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(MultiSearchAction.NAME).test(apmSampledTracesIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(IndicesStatsAction.NAME).test(apmSampledTracesIndex), is(true));
        assertThat(role.indices().allowedIndicesMatcher(DeleteIndexAction.NAME).test(apmSampledTracesIndex), is(false));
        assertThat(role.indices().allowedIndicesMatcher(UpdateSettingsAction.NAME).test(apmSampledTracesIndex), is(false));

        final String privilegeName = randomAlphaOfLengthBetween(3, 16);
        assertThat(
            role.application()
                .grants(ApplicationPrivilegeTests.createPrivilege(kibanaApplication, privilegeName, allowedApplicationActionPattern), "*"),
            is(true)
        );

        final String otherApplication = randomValueOtherThanMany(s -> s.startsWith("kibana"), () -> randomAlphaOfLengthBetween(3, 8))
            + "-"
            + randomAlphaOfLengthBetween(8, 24);
        assertThat(
            role.application()
                .grants(ApplicationPrivilegeTests.createPrivilege(otherApplication, privilegeName, allowedApplicationActionPattern), "*"),
            is(false)
        );

        assertThat(
            role.application()
                .grants(
                    ApplicationPrivilegeTests.createPrivilege(
                        kibanaApplication,
                        privilegeName,
                        randomArray(1, 5, String[]::new, () -> randomAlphaOfLengthBetween(3, 16))
                    ),
                    "*"
                ),
            is(false)
        );
    }

    public void testElasticServiceAccount() {
        final String serviceName = randomAlphaOfLengthBetween(3, 8);
        final String principal = ElasticServiceAccounts.NAMESPACE + "/" + serviceName;
        final RoleDescriptor roleDescriptor1 = new RoleDescriptor(principal, null, null, null);
        final ElasticServiceAccount serviceAccount = new ElasticServiceAccount(serviceName, roleDescriptor1);
        assertThat(serviceAccount.id(), equalTo(new ServiceAccount.ServiceAccountId(ElasticServiceAccounts.NAMESPACE, serviceName)));
        assertThat(serviceAccount.roleDescriptor(), equalTo(roleDescriptor1));
        assertThat(
            serviceAccount.asUser(),
            equalTo(
                new User(
                    principal,
                    Strings.EMPTY_ARRAY,
                    "Service account - " + principal,
                    null,
                    Map.of("_elastic_service_account", true),
                    true
                )
            )
        );

        final NullPointerException e1 = expectThrows(NullPointerException.class, () -> new ElasticServiceAccount(serviceName, null));
        assertThat(e1.getMessage(), containsString("Role descriptor cannot be null"));

        final RoleDescriptor roleDescriptor2 = new RoleDescriptor(randomAlphaOfLengthBetween(6, 16), null, null, null);
        final IllegalArgumentException e2 = expectThrows(
            IllegalArgumentException.class,
            () -> new ElasticServiceAccount(serviceName, roleDescriptor2)
        );
        assertThat(
            e2.getMessage(),
            containsString(
                "the provided role descriptor ["
                    + roleDescriptor2.getName()
                    + "] must have the same name as the service account ["
                    + principal
                    + "]"
            )
        );
    }

    public void testElasticEnterpriseSearchServerAccount() {
        final Role role = Role.buildFromRoleDescriptor(
            ElasticServiceAccounts.ACCOUNTS.get("elastic/enterprise-search-server").roleDescriptor(),
            new FieldPermissionsCache(Settings.EMPTY),
            RESTRICTED_INDICES
        );

        final Authentication authentication = AuthenticationTestHelper.builder().serviceAccount().build();
        final TransportRequest request = mock(TransportRequest.class);

        // manage
        assertThat(role.cluster().check(ClusterUpdateSettingsAction.NAME, request, authentication), is(true));

        // manage_security
        assertThat(
            role.cluster()
                .check(CreateApiKeyAction.NAME, new CreateApiKeyRequest(randomAlphaOfLengthBetween(3, 8), null, null), authentication),
            is(true)
        );
        assertThat(
            role.cluster().check(GetApiKeyAction.NAME, GetApiKeyRequest.builder().ownedByAuthenticatedUser().build(), authentication),
            is(true)
        );
        assertThat(role.cluster().check(InvalidateApiKeyAction.NAME, InvalidateApiKeyRequest.forOwnedApiKeys(), authentication), is(true));

        assertThat(role.cluster().check(PutUserAction.NAME, request, authentication), is(true));
        assertThat(role.cluster().check(PutRoleAction.NAME, request, authentication), is(true));

        // manage_index_templates
        assertThat(role.cluster().check(PutIndexTemplateAction.NAME, request, authentication), is(true));
        assertThat(role.cluster().check(GetIndexTemplatesAction.NAME, request, authentication), is(true));
        assertThat(role.cluster().check(DeleteIndexTemplateAction.NAME, request, authentication), is(true));

        // monitoring
        assertThat(role.cluster().check(MonitoringBulkAction.NAME, request, authentication), is(true));
        assertThat(role.cluster().check(ClusterHealthAction.NAME, request, authentication), is(true));

        // manage_ilm
        assertThat(role.cluster().check(GetLifecycleAction.NAME, request, authentication), is(true));
        assertThat(role.cluster().check(PutLifecycleAction.NAME, request, authentication), is(true));

        List.of(
            "search-" + randomAlphaOfLengthBetween(1, 20),
            ".search-acl-filter-" + randomAlphaOfLengthBetween(1, 20),
            ".elastic-analytics-collections",
            ".ent-search-" + randomAlphaOfLengthBetween(1, 20),
            ".monitoring-ent-search-" + randomAlphaOfLengthBetween(1, 20),
            "metricbeat-ent-search-" + randomAlphaOfLengthBetween(1, 20),
            "enterprise-search-" + randomAlphaOfLengthBetween(1, 20),
            "logs-app_search.analytics-default",
            "logs-elastic_analytics.events-" + randomAlphaOfLengthBetween(1, 20),
            "logs-enterprise_search.api-default",
            "logs-enterprise_search.audit-default",
            "logs-app_search.search_relevance_suggestions-default",
            "logs-crawler-default",
            "logs-workplace_search.analytics-default",
            "logs-workplace_search.content_events-default",
            ".elastic-connectors*",
            "logs-elastic_crawler-default"
        ).forEach(index -> {
            final IndexAbstraction enterpriseSearchIndex = mockIndexAbstraction(index);
            assertThat(role.indices().allowedIndicesMatcher(AutoCreateAction.NAME).test(enterpriseSearchIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(CreateIndexAction.NAME).test(enterpriseSearchIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(DeleteAction.NAME).test(enterpriseSearchIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(DeleteIndexAction.NAME).test(enterpriseSearchIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(IndexAction.NAME).test(enterpriseSearchIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(BulkAction.NAME).test(enterpriseSearchIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(GetAction.NAME).test(enterpriseSearchIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(MultiGetAction.NAME).test(enterpriseSearchIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(SearchAction.NAME).test(enterpriseSearchIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(MultiSearchAction.NAME).test(enterpriseSearchIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(IndicesStatsAction.NAME).test(enterpriseSearchIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(UpdateSettingsAction.NAME).test(enterpriseSearchIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher(RefreshAction.NAME).test(enterpriseSearchIndex), is(true));
            assertThat(role.indices().allowedIndicesMatcher("indices:foo").test(enterpriseSearchIndex), is(false));
        });
    }

    private IndexAbstraction mockIndexAbstraction(String name) {
        IndexAbstraction mock = mock(IndexAbstraction.class);
        when(mock.getName()).thenReturn(name);
        when(mock.getType()).thenReturn(
            randomFrom(IndexAbstraction.Type.CONCRETE_INDEX, IndexAbstraction.Type.ALIAS, IndexAbstraction.Type.DATA_STREAM)
        );
        return mock;
    }

}
