package org.highmed.dsf.fhir.search.parameters.user;

import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.highmed.dsf.fhir.authentication.User;
import org.highmed.dsf.fhir.authentication.UserRole;

abstract class AbstractMetaTagAuthorizationRoleUserFilter extends AbstractUserFilter
{
	public AbstractMetaTagAuthorizationRoleUserFilter(User user, String resourceColumn)
	{
		super(user, resourceColumn);
	}

	@Override
	public String getFilterQuery()
	{
		switch (user.getRole())
		{
			case LOCAL: // parameterIndex 1 and 2
				return "(" + resourceColumn + "->'meta'->'tag' @> ?::jsonb OR " + resourceColumn
						+ "->'meta'->'tag' @> ?::jsonb)";

			case REMOTE: // parameterIndex 1
				return resourceColumn + "->'meta'->'tag' @> ?::jsonb";

			default:
				throw new IllegalStateException(
						"Value " + user.getRole() + " of " + UserRole.class.getName() + " not implemented");
		}
	}

	@Override
	public int getSqlParameterCount()
	{
		switch (user.getRole())
		{
			case LOCAL:
				return 2;

			case REMOTE:
				return 1;

			default:
				throw new IllegalStateException(
						"Value " + user.getRole() + " of " + UserRole.class.getName() + " not implemented");
		}
	}

	@Override
	public void modifyStatement(int parameterIndex, int subqueryParameterIndex, PreparedStatement statement)
			throws SQLException
	{
		if (subqueryParameterIndex == 1)
			statement.setString(parameterIndex, "[{\"code\": \"" + AUTHORIZATION_ROLE_VALUE_REMOTE
					+ "\", \"system\": \"" + AUTHORIZATION_ROLE_SYSTEM + "\"}]");
		else if (subqueryParameterIndex == 2)
			statement.setString(parameterIndex, "[{\"code\": \"" + AUTHORIZATION_ROLE_VALUE_LOCAL + "\", \"system\": \""
					+ AUTHORIZATION_ROLE_SYSTEM + "\"}]");
		else
			throw new IllegalStateException("Unexpected paramterIndex " + parameterIndex);
	}
}
