package org.highmed.dsf.fhir.dao.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.apache.commons.dbcp2.BasicDataSource;
import org.highmed.dsf.fhir.authentication.User;
import org.highmed.dsf.fhir.dao.StructureDefinitionDao;
import org.highmed.dsf.fhir.search.SearchQueryUserFilter;
import org.highmed.dsf.fhir.search.parameters.StructureDefinitionIdentifier;
import org.highmed.dsf.fhir.search.parameters.StructureDefinitionStatus;
import org.highmed.dsf.fhir.search.parameters.StructureDefinitionUrl;
import org.highmed.dsf.fhir.search.parameters.StructureDefinitionVersion;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.context.FhirContext;

abstract class AbstractStructureDefinitionDaoJdbc extends AbstractResourceDaoJdbc<StructureDefinition>
		implements StructureDefinitionDao
{
	private static final Logger logger = LoggerFactory.getLogger(AbstractStructureDefinitionDaoJdbc.class);

	private final ReadByUrlDaoJdbc<StructureDefinition> readByUrl;

	public AbstractStructureDefinitionDaoJdbc(BasicDataSource dataSource, FhirContext fhirContext, String resourceTable,
			String resourceColumn, String resourceIdColumn, Function<User, SearchQueryUserFilter> userFilter)
	{
		super(dataSource, fhirContext, StructureDefinition.class, resourceTable, resourceColumn, resourceIdColumn,
				userFilter,
				with(() -> new StructureDefinitionIdentifier(resourceColumn),
						() -> new StructureDefinitionStatus(resourceColumn),
						() -> new StructureDefinitionUrl(resourceColumn),
						() -> new StructureDefinitionVersion(resourceColumn)),
				with());

		readByUrl = new ReadByUrlDaoJdbc<StructureDefinition>(this::getDataSource, this::getResource, resourceTable,
				resourceColumn, resourceIdColumn);
	}

	@Override
	public List<StructureDefinition> readAll() throws SQLException
	{
		try (Connection connection = getDataSource().getConnection())
		{
			return readAllWithTransaction(connection);
		}
	}

	@Override
	public List<StructureDefinition> readAllWithTransaction(Connection connection) throws SQLException
	{
		Objects.requireNonNull(connection, "connection");

		try (PreparedStatement statement = connection
				.prepareStatement("SELECT DISTINCT ON(" + getResourceIdColumn() + ") " + getResourceColumn() + " FROM "
						+ getResourceTable() + " WHERE NOT deleted ORDER BY " + getResourceIdColumn() + ", version"))
		{
			logger.trace("Executing query '{}'", statement);
			try (ResultSet result = statement.executeQuery())
			{
				List<StructureDefinition> all = new ArrayList<>();

				while (result.next())
					all.add(getResource(result, 1));

				return all;
			}
		}
	}

	@Override
	public Optional<StructureDefinition> readByUrlAndVersion(String urlAndVersion) throws SQLException
	{
		return readByUrl.readByUrlAndVersion(urlAndVersion);
	}

	@Override
	public Optional<StructureDefinition> readByUrlAndVersionWithTransaction(Connection connection, String urlAndVersion)
			throws SQLException
	{
		return readByUrl.readByUrlAndVersionWithTransaction(connection, urlAndVersion);
	}

	@Override
	public Optional<StructureDefinition> readByUrlAndVersion(String url, String version) throws SQLException
	{
		return readByUrl.readByUrlAndVersion(url, version);
	}

	@Override
	public Optional<StructureDefinition> readByUrlAndVersionWithTransaction(Connection connection, String url,
			String version) throws SQLException
	{
		return readByUrl.readByUrlAndVersionWithTransaction(connection, url, version);
	}
}
