/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.organization.discovery.service.listener;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.identity.application.authentication.framework.ApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.FederatedApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.config.model.AuthenticatorConfig;
import org.wso2.carbon.identity.application.authentication.framework.config.model.ExternalIdPConfig;
import org.wso2.carbon.identity.application.authentication.framework.config.model.SequenceConfig;
import org.wso2.carbon.identity.application.authentication.framework.config.model.StepConfig;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.PostAuthenticationFailedException;
import org.wso2.carbon.identity.application.authentication.framework.handler.request.AbstractPostAuthnHandler;
import org.wso2.carbon.identity.application.authentication.framework.handler.request.PostAuthnHandlerFlowStatus;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkErrorConstants;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.common.model.ClaimMapping;
import org.wso2.carbon.identity.claim.metadata.mgt.ClaimMetadataHandler;
import org.wso2.carbon.identity.claim.metadata.mgt.exception.ClaimMetadataException;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.organization.config.service.exception.OrganizationConfigClientException;
import org.wso2.carbon.identity.organization.config.service.exception.OrganizationConfigException;
import org.wso2.carbon.identity.organization.config.service.model.ConfigProperty;
import org.wso2.carbon.identity.organization.config.service.model.DiscoveryConfig;
import org.wso2.carbon.identity.organization.discovery.service.OrganizationDiscoveryManager;
import org.wso2.carbon.identity.organization.discovery.service.OrganizationDiscoveryManagerImpl;
import org.wso2.carbon.identity.organization.discovery.service.internal.OrganizationDiscoveryServiceHolder;
import org.wso2.carbon.identity.organization.discovery.service.model.OrgDiscoveryAttribute;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_INVALID_EMAIL_DOMAIN;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_NO_EMAIL_ATTRIBUTE_FOUND;
import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_WHILE_RETRIEVING_ORG_DISCOVERY_ATTRIBUTES;

/**
 * Responsible for validating the email domain of the user during the authentication flow.
 */
public class EmailDomainValidationHandler extends AbstractPostAuthnHandler {

    private static final Log LOG = LogFactory.getLog(EmailDomainValidationHandler.class);
    private static final String EMAIL_DOMAIN_ENABLE = "emailDomain.enable";
    public static final String EMAIL_DOMAIN = "emailDomain";
    private final OrganizationDiscoveryManager organizationDiscoveryManager = new OrganizationDiscoveryManagerImpl();

    private EmailDomainValidationHandler() {

    }

    private static class Holder {

        private static final EmailDomainValidationHandler INSTANCE = new EmailDomainValidationHandler();
    }

    public static EmailDomainValidationHandler getInstance() {

        return Holder.INSTANCE;
    }

    @Override
    public boolean isEnabled() {

        if (!super.isEnabled()) {
            return false;
        }

        try {
            OrganizationManager organizationManager =
                    OrganizationDiscoveryServiceHolder.getInstance().getOrganizationManager();
            String organizationId = organizationManager.resolveOrganizationId(CarbonContext
                    .getThreadLocalCarbonContext().getTenantDomain());

            if (organizationManager.isPrimaryOrganization(organizationId)) {
                // Skip email domain validation since email domains cannot be mapped to primary organizations.
                return false;
            }
            organizationId = organizationManager.getPrimaryOrganizationId(organizationId);

            return isEmailDomainDiscoveryEnabled(organizationId);
        } catch (OrganizationConfigClientException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("No organization discovery configurations found for tenant domain: " + CarbonContext
                        .getThreadLocalCarbonContext().getTenantDomain());
            }
            return false;
        } catch (OrganizationManagementException | OrganizationConfigException e) {
            LOG.error("Error while retrieving organization discovery configuration.", e);
            return false;
        }
    }

    @Override
    public int getPriority() {

        int priority = super.getPriority();
        if (priority == -1) {
            priority = 15;
        }
        return priority;
    }

    @Override
    public PostAuthnHandlerFlowStatus handle(HttpServletRequest request, HttpServletResponse response,
                                             AuthenticationContext context) throws PostAuthenticationFailedException {

        SequenceConfig sequenceConfig = context.getSequenceConfig();
        for (Map.Entry<Integer, StepConfig> entry : sequenceConfig.getStepMap().entrySet()) {
            StepConfig stepConfig = entry.getValue();
            AuthenticatorConfig authenticatorConfig = stepConfig.getAuthenticatedAutenticator();
            if (authenticatorConfig == null) {
                continue;
            }

            ApplicationAuthenticator authenticator = authenticatorConfig.getApplicationAuthenticator();
            if (authenticator instanceof FederatedApplicationAuthenticator) {
                Map<String, String> localClaimValues;
                if (stepConfig.isSubjectAttributeStep()) {
                    localClaimValues =
                            (Map<String, String>) context.getProperty(FrameworkConstants.UNFILTERED_LOCAL_CLAIM_VALUES);
                } else {
                    /*
                     * Need to validate even if this is not the subject attribute step since
                     * jit provisioning will happen in both scenarios.
                     */
                    localClaimValues = getLocalClaimValuesOfIDPInNonAttributeSelectionStep(context, stepConfig,
                            context.getExternalIdP());
                }

                Optional<String> emailDomain =
                        extractEmailDomain(localClaimValues.get(FrameworkConstants.EMAIL_ADDRESS_CLAIM));

                if (!emailDomain.isPresent()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Email address not found or is not in the correct format." +
                                " Email domain validation failed for tenant: " + context.getTenantDomain());
                    }
                    throw new PostAuthenticationFailedException(ERROR_CODE_NO_EMAIL_ATTRIBUTE_FOUND.getCode(),
                            ERROR_CODE_NO_EMAIL_ATTRIBUTE_FOUND.getDescription());
                }

                if (!isValidEmailDomain(context, emailDomain.get())) {
                    throw new PostAuthenticationFailedException(ERROR_CODE_INVALID_EMAIL_DOMAIN.getCode(),
                            String.format(ERROR_CODE_INVALID_EMAIL_DOMAIN.getDescription(), context.getTenantDomain()));
                }
            }
        }
        return PostAuthnHandlerFlowStatus.SUCCESS_COMPLETED;
    }

    private boolean isEmailDomainDiscoveryEnabled(String primaryOrganizationId)
            throws OrganizationConfigException, OrganizationManagementException {

        String tenantDomain = OrganizationDiscoveryServiceHolder.getInstance().getOrganizationManager()
                .resolveTenantDomain(primaryOrganizationId);

        DiscoveryConfig discoveryConfiguration =
                OrganizationDiscoveryServiceHolder.getInstance().getOrganizationConfigManager().
                        getDiscoveryConfigurationByTenantId(IdentityTenantUtil.getTenantId(tenantDomain));
        List<ConfigProperty> configProperties = discoveryConfiguration.getConfigProperties();
        for (ConfigProperty configProperty : configProperties) {
            if (EMAIL_DOMAIN_ENABLE.equals(configProperty.getKey())) {
                return Boolean.parseBoolean(configProperty.getValue());
            }
        }
        return false;
    }

    private boolean isValidEmailDomain(AuthenticationContext context, String emaildomain)
            throws PostAuthenticationFailedException {

        try {
            List<OrgDiscoveryAttribute> organizationDiscoveryAttributes =
                    organizationDiscoveryManager.getOrganizationDiscoveryAttributes(context.getTenantDomain(), false);

            if (organizationDiscoveryAttributes.isEmpty()) {
                LOG.debug("No email domains are mapped to the organization. Skipping email domain validation.");
                return true;
            }

            for (OrgDiscoveryAttribute orgDiscoveryAttribute : organizationDiscoveryAttributes) {
                if (!EMAIL_DOMAIN.equals(orgDiscoveryAttribute.getType())) {
                    continue;
                }

                List<String> mappedEmailDomains = orgDiscoveryAttribute.getValues();
                if (mappedEmailDomains != null && !mappedEmailDomains.contains(emaildomain)) {
                    return false;
                }
            }
        } catch (OrganizationManagementException e) {
            LOG.error("Error while retrieving organization discovery attributes for tenant: " +
                    context.getTenantDomain(), e);
            throw new PostAuthenticationFailedException(ERROR_WHILE_RETRIEVING_ORG_DISCOVERY_ATTRIBUTES.getCode(),
                    String.format(ERROR_WHILE_RETRIEVING_ORG_DISCOVERY_ATTRIBUTES.getDescription(),
                            context.getTenantDomain()), e);
        }
        return true;
    }

    private Optional<String> extractEmailDomain(String email) {

        if (StringUtils.isBlank(email)) {
            return Optional.empty();
        }

        String[] emailSplit = email.split("@");
        return emailSplit.length == 2 ? Optional.of(emailSplit[1]) : Optional.empty();
    }

    /**
     * Uses to get local claim values of an authenticated user from an IDP in non attribute selection steps.
     *
     * @param context           Authentication Context.
     * @param stepConfig        Current step configuration.
     * @param externalIdPConfig Identity providers config.
     * @return Mapped federated user values to local claims.
     * @throws PostAuthenticationFailedException Post Authentication failed exception.
     */
    private Map<String, String> getLocalClaimValuesOfIDPInNonAttributeSelectionStep(AuthenticationContext context,
                                                                                    StepConfig stepConfig,
                                                                                    ExternalIdPConfig externalIdPConfig)
            throws PostAuthenticationFailedException {

        boolean useDefaultIdpDialect = externalIdPConfig.useDefaultLocalIdpDialect();
        ApplicationAuthenticator authenticator =
                stepConfig.getAuthenticatedAutenticator().getApplicationAuthenticator();
        String idPStandardDialect = authenticator.getClaimDialectURI();
        Map<ClaimMapping, String> extAttrs = stepConfig.getAuthenticatedUser().getUserAttributes();
        Map<String, String> originalExternalAttributeValueMap = FrameworkUtils.getClaimMappings(extAttrs, false);
        Map<String, String> claimMapping = new HashMap<>();
        Map<String, String> localClaimValues = new HashMap<>();
        if (useDefaultIdpDialect && StringUtils.isNotBlank(idPStandardDialect)) {
            try {
                claimMapping = ClaimMetadataHandler.getInstance()
                        .getMappingsMapFromOtherDialectToCarbon(idPStandardDialect,
                                originalExternalAttributeValueMap.keySet(), context.getTenantDomain(),
                                true);
            } catch (ClaimMetadataException e) {
                throw new PostAuthenticationFailedException(
                        FrameworkErrorConstants.ErrorMessages.ERROR_WHILE_HANDLING_CLAIM_MAPPINGS.getCode(),
                        FrameworkErrorConstants.ErrorMessages.ERROR_WHILE_HANDLING_CLAIM_MAPPINGS.getMessage(), e);
            }
        } else {
            ClaimMapping[] customClaimMapping = context.getExternalIdP().getClaimMappings();
            for (ClaimMapping externalClaim : customClaimMapping) {
                if (originalExternalAttributeValueMap.containsKey(externalClaim.getRemoteClaim().getClaimUri())) {
                    claimMapping.put(externalClaim.getLocalClaim().getClaimUri(),
                            externalClaim.getRemoteClaim().getClaimUri());
                }
            }
        }

        if (claimMapping != null && !claimMapping.isEmpty()) {
            for (Map.Entry<String, String> entry : claimMapping.entrySet()) {
                if (originalExternalAttributeValueMap.containsKey(entry.getValue()) &&
                        originalExternalAttributeValueMap.get(entry.getValue()) != null) {
                    localClaimValues.put(entry.getKey(), originalExternalAttributeValueMap.get(entry.getValue()));
                }
            }
        }
        return localClaimValues;
    }
}
