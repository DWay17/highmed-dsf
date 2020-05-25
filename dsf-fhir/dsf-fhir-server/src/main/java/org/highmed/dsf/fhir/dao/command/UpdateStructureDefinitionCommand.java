package org.highmed.dsf.fhir.dao.command;

import java.sql.Connection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.highmed.dsf.fhir.authentication.User;
import org.highmed.dsf.fhir.dao.StructureDefinitionDao;
import org.highmed.dsf.fhir.dao.StructureDefinitionSnapshotDao;
import org.highmed.dsf.fhir.event.EventGenerator;
import org.highmed.dsf.fhir.event.EventManager;
import org.highmed.dsf.fhir.function.ConsumerWithSqlAndResourceNotFoundException;
import org.highmed.dsf.fhir.help.ExceptionHandler;
import org.highmed.dsf.fhir.help.ParameterConverter;
import org.highmed.dsf.fhir.help.ResponseGenerator;
import org.highmed.dsf.fhir.service.ReferenceCleaner;
import org.highmed.dsf.fhir.service.ReferenceExtractor;
import org.highmed.dsf.fhir.service.ReferenceResolver;
import org.highmed.dsf.fhir.service.SnapshotDependencies;
import org.highmed.dsf.fhir.service.SnapshotDependencyAnalyzer;
import org.highmed.dsf.fhir.service.SnapshotGenerator;
import org.highmed.dsf.fhir.service.SnapshotGenerator.SnapshotWithValidationMessages;
import org.highmed.dsf.fhir.service.SnapshotInfo;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateStructureDefinitionCommand extends UpdateCommand<StructureDefinition, StructureDefinitionDao>
		implements Command
{
	private static final Logger logger = LoggerFactory.getLogger(UpdateStructureDefinitionCommand.class);

	private final StructureDefinitionSnapshotDao snapshotDao;
	private final Function<Connection, SnapshotGenerator> snapshotGenerator;
	private final SnapshotDependencyAnalyzer snapshotDependencyAnalyzer;

	private StructureDefinition resourceWithSnapshot;

	public UpdateStructureDefinitionCommand(int index, User user, Bundle bundle, BundleEntryComponent entry,
			String serverBase, AuthorizationHelper authorizationHelper, StructureDefinition resource,
			StructureDefinitionDao dao, ExceptionHandler exceptionHandler, ParameterConverter parameterConverter,
			ResponseGenerator responseGenerator, ReferenceExtractor referenceExtractor,
			ReferenceResolver referenceResolver, ReferenceCleaner referenceCleaner, EventManager eventManager,
			EventGenerator eventGenerator, StructureDefinitionSnapshotDao snapshotDao,
			Function<Connection, SnapshotGenerator> snapshotGenerator,
			SnapshotDependencyAnalyzer snapshotDependencyAnalyzer)
	{
		super(index, user, bundle, entry, serverBase, authorizationHelper, resource, dao, exceptionHandler,
				parameterConverter, responseGenerator, referenceExtractor, referenceResolver, referenceCleaner,
				eventManager, eventGenerator);

		this.snapshotDao = snapshotDao;
		this.snapshotGenerator = snapshotGenerator;
		this.snapshotDependencyAnalyzer = snapshotDependencyAnalyzer;
	}

	@Override
	public void preExecute(Map<String, IdType> idTranslationTable)
	{
		resourceWithSnapshot = resource.hasSnapshot() ? resource.copy() : null;
		resource.setSnapshot(null);

		super.preExecute(idTranslationTable);
	}

	@Override
	public Optional<BundleEntryComponent> postExecute(Connection connection)
	{
		if (resourceWithSnapshot != null)
		{
			if (updatedResource != null)
				resourceWithSnapshot.setIdElement(updatedResource.getIdElement().copy());

			handleSnapshot(connection, resourceWithSnapshot,
					info -> snapshotDao.updateWithTransaction(connection, resourceWithSnapshot, info));
		}
		else if (updatedResource != null)
		{
			try
			{
				SnapshotWithValidationMessages s = snapshotGenerator.apply(connection)
						.generateSnapshot(updatedResource);

				if (s != null && s.getSnapshot() != null && s.getMessages().isEmpty())
					handleSnapshot(connection, s.getSnapshot(),
							info -> snapshotDao.updateWithTransaction(connection, s.getSnapshot(), info));
			}
			catch (Exception e)
			{
				logger.warn("Error while generating snapshot for StructureDefinition with id "
						+ updatedResource.getIdElement().getIdPart(), e);
			}
		}

		return super.postExecute(connection);
	}

	private void handleSnapshot(Connection connection, StructureDefinition snapshot,
			ConsumerWithSqlAndResourceNotFoundException<SnapshotInfo> dbOp)
	{
		SnapshotDependencies dependencies = snapshotDependencyAnalyzer.analyzeSnapshotDependencies(snapshot);

		exceptionHandler.catchAndLogSqlException(
				() -> snapshotDao.deleteAllByDependencyWithTransaction(connection, snapshot.getUrl()));

		exceptionHandler.catchAndLogSqlAndResourceNotFoundException(resource.getResourceType().name(),
				() -> dbOp.accept(new SnapshotInfo(dependencies)));
	}
}
