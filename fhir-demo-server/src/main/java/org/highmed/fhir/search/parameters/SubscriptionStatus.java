package org.highmed.fhir.search.parameters;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.UriBuilder;

import org.highmed.fhir.search.parameters.basic.AbstractTokenParameter;
import org.highmed.fhir.search.parameters.basic.SearchParameter;
import org.highmed.fhir.search.parameters.basic.SearchParameter.SearchParameterDefinition;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r4.model.Enumerations.SearchParamType;
import org.hl7.fhir.r4.model.Subscription;

import com.google.common.base.Objects;

@SearchParameterDefinition(name = SubscriptionStatus.PARAMETER_NAME, definition = "http://hl7.org/fhir/SearchParameter/Subscription.status", type = SearchParamType.TOKEN, documentation = "Search by subscription status")
public class SubscriptionStatus extends AbstractTokenParameter<Subscription>
{
	public static final String PARAMETER_NAME = "status";

	private org.hl7.fhir.r4.model.Subscription.SubscriptionStatus status;

	public SubscriptionStatus()
	{
		super(Subscription.class, PARAMETER_NAME);
	}

	@Override
	protected void configureSearchParameter(Map<String, List<String>> queryParameters)
	{
		super.configureSearchParameter(queryParameters);

		if (valueAndType != null && valueAndType.type == TokenSearchType.CODE)
			status = toStatus(valueAndType.codeValue);
	}

	private org.hl7.fhir.r4.model.Subscription.SubscriptionStatus toStatus(String status)
	{
		if (status == null || status.isBlank())
			return null;

		try
		{
			return org.hl7.fhir.r4.model.Subscription.SubscriptionStatus.fromCode(status);
		}
		catch (FHIRException e)
		{
			return null;
		}
	}

	@Override
	public boolean isDefined()
	{
		return super.isDefined() && status != null;
	}

	@Override
	public String getFilterQuery()
	{
		return "subscription->>'status' = ?";
	}

	@Override
	public int getSqlParameterCount()
	{
		return 1;
	}

	@Override
	public void modifyStatement(int parameterIndex, int subqueryParameterIndex, PreparedStatement statement)
			throws SQLException
	{
		statement.setString(parameterIndex, status.toCode());
	}

	@Override
	public void modifyBundleUri(UriBuilder bundleUri)
	{
		bundleUri.replaceQueryParam(PARAMETER_NAME, status.toCode());
	}

	@Override
	public boolean matches(Subscription resource)
	{
		if (!isDefined())
			throw SearchParameter.notDefined();

		return Objects.equal(resource.getStatus(), status);
	}

	@Override
	protected String getSortSql(String sortDirectionWithSpacePrefix)
	{
		return "subscription->>'status'" + sortDirectionWithSpacePrefix;
	}
}
