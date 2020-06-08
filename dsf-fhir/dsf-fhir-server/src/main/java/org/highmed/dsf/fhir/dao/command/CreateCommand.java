package org.highmed.dsf.fhir.dao.command;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.highmed.dsf.fhir.authentication.User;
import org.highmed.dsf.fhir.dao.ResourceDao;
import org.highmed.dsf.fhir.dao.exception.ResourceNotFoundException;
import org.highmed.dsf.fhir.event.EventGenerator;
import org.highmed.dsf.fhir.event.EventManager;
import org.highmed.dsf.fhir.help.ExceptionHandler;
import org.highmed.dsf.fhir.help.ParameterConverter;
import org.highmed.dsf.fhir.help.ResponseGenerator;
import org.highmed.dsf.fhir.search.PartialResult;
import org.highmed.dsf.fhir.search.SearchQuery;
import org.highmed.dsf.fhir.search.SearchQueryParameterError;
import org.highmed.dsf.fhir.service.ReferenceCleaner;
import org.highmed.dsf.fhir.service.ReferenceExtractor;
import org.highmed.dsf.fhir.service.ReferenceResolver;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleEntryResponseComponent;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import ca.uhn.fhir.rest.api.Constants;

public class CreateCommand<R extends Resource, D extends ResourceDao<R>> extends AbstractCommandWithResource<R, D>
		implements Command
{
	private static final Logger logger = LoggerFactory.getLogger(CreateCommand.class);

	private final ResponseGenerator responseGenerator;
	private final ResolveReferencesHelper<R> resolveReferencesHelper;
	private final ReferenceCleaner referenceCleaner;

	protected final EventManager eventManager;
	protected final EventGenerator eventGenerator;

	protected R createdResource;
	protected Response responseResult;

	public CreateCommand(int index, User user, Bundle bundle, BundleEntryComponent entry, String serverBase,
			AuthorizationHelper authorizationHelper, R resource, D dao, ExceptionHandler exceptionHandler,
			ParameterConverter parameterConverter, ResponseGenerator responseGenerator,
			ReferenceExtractor referenceExtractor, ReferenceResolver referenceResolver,
			ReferenceCleaner referenceCleaner, EventManager eventManager, EventGenerator eventGenerator)
	{
		super(2, index, user, bundle, entry, serverBase, authorizationHelper, resource, dao, exceptionHandler,
				parameterConverter);

		this.responseGenerator = responseGenerator;
		resolveReferencesHelper = new ResolveReferencesHelper<R>(index, user, serverBase, referenceExtractor,
				referenceResolver, responseGenerator);
		this.referenceCleaner = referenceCleaner;

		this.eventManager = eventManager;
		this.eventGenerator = eventGenerator;
	}

	@Override
	public void preExecute(Map<String, IdType> idTranslationTable)
	{
		UriComponents eruComponentes = UriComponentsBuilder.fromUriString(entry.getRequest().getUrl()).build();

		// check standard update request url: Patient
		if (eruComponentes.getPathSegments().size() == 1 && eruComponentes.getQueryParams().isEmpty())
		{
			if (!entry.getFullUrl().startsWith(URL_UUID_PREFIX))
				throw new WebApplicationException(
						responseGenerator.badCreateRequestUrl(index, entry.getRequest().getUrl()));
			else if (resource.hasIdElement() && !resource.getIdElement().getValue().startsWith(URL_UUID_PREFIX))
				throw new WebApplicationException(responseGenerator.bundleEntryBadResourceId(index,
						resource.getResourceType().name(), URL_UUID_PREFIX));
			else if (resource.hasIdElement() && !entry.getFullUrl().equals(resource.getIdElement().getValue()))
				throw new WebApplicationException(responseGenerator.badBundleEntryFullUrlVsResourceId(index,
						entry.getFullUrl(), resource.getIdElement().getValue()));
		}

		// all other request urls
		else
			throw new WebApplicationException(
					responseGenerator.badCreateRequestUrl(index, entry.getRequest().getUrl()));
	}

	@Override
	public void execute(Map<String, IdType> idTranslationTable, Connection connection)
			throws SQLException, WebApplicationException
	{
		@SuppressWarnings("unchecked")
		R copy = (R) resource.copy();
		resolveReferencesHelper.resolveReferencesIgnoreAndLogExceptions(idTranslationTable, connection, copy);

		authorizationHelper.checkCreateAllowed(connection, user, copy);

		Optional<Resource> exists = checkAlreadyExists(connection, entry.getRequest().getIfNoneExist(),
				resource.getResourceType());

		if (exists.isEmpty())
		{
			UUID id = UUID.randomUUID();
			idTranslationTable.put(entry.getFullUrl(),
					new IdType(resource.getResourceType().toString(), id.toString()));
			createdResource = dao.createWithTransactionAndId(connection, resource, id);
		}
		else
		{
			Resource existingResource = exists.get();
			idTranslationTable.put(entry.getFullUrl(), new IdType(existingResource.getResourceType().toString(),
					existingResource.getIdElement().getIdPart()));
			responseResult = responseGenerator.oneExists(existingResource, entry.getRequest().getIfNoneExist());
		}
	}

	private Optional<Resource> checkAlreadyExists(Connection connection, String ifNoneExist, ResourceType resourceType)
			throws WebApplicationException
	{
		if (ifNoneExist == null)
			return Optional.empty();

		if (ifNoneExist.isBlank())
			throw new WebApplicationException(responseGenerator.badIfNoneExistHeaderValue(ifNoneExist));

		if (!ifNoneExist.contains("?"))
			ifNoneExist = '?' + ifNoneExist;

		UriComponents componentes = UriComponentsBuilder.fromUriString(ifNoneExist).build();
		String path = componentes.getPath();
		if (path != null && !path.isBlank())
			throw new WebApplicationException(responseGenerator.badIfNoneExistHeaderValue(ifNoneExist));

		Map<String, List<String>> queryParameters = parameterConverter
				.urlDecodeQueryParameters(componentes.getQueryParams());
		if (Arrays.stream(SearchQuery.STANDARD_PARAMETERS).anyMatch(queryParameters::containsKey))
		{
			logger.warn(
					"{} Header contains query parameter not applicable in this conditional create context: '{}', parameters {} will be ignored",
					Constants.HEADER_IF_NONE_EXIST, ifNoneExist, Arrays.toString(SearchQuery.STANDARD_PARAMETERS));

			queryParameters = queryParameters.entrySet().stream()
					.filter(e -> !Arrays.stream(SearchQuery.STANDARD_PARAMETERS).anyMatch(p -> p.equals(e.getKey())))
					.collect(Collectors.toMap(Entry::getKey, Entry::getValue));
		}

		SearchQuery<R> query = dao.createSearchQuery(user, 1, 1);
		query.configureParameters(queryParameters);

		List<SearchQueryParameterError> unsupportedQueryParameters = query
				.getUnsupportedQueryParameters(queryParameters);
		if (!unsupportedQueryParameters.isEmpty())
			throw new WebApplicationException(
					responseGenerator.badIfNoneExistHeaderValue(ifNoneExist, unsupportedQueryParameters));

		PartialResult<R> result = exceptionHandler
				.handleSqlException(() -> dao.searchWithTransaction(connection, query));
		if (result.getOverallCount() == 1)
			return Optional.of(result.getPartialResult().get(0));
		else if (result.getOverallCount() > 1)
			throw new WebApplicationException(responseGenerator.multipleExists(resourceType.name(), ifNoneExist));

		return Optional.empty();
	}

	@Override
	public Optional<BundleEntryComponent> postExecute(Connection connection)
	{
		if (responseResult == null)
		{
			try
			{
				// retrieving the latest resource from db to include updated references
				Resource createdResourceWithResolvedReferences = latestOrErrorIfDeletedOrNotFound(connection,
						createdResource);
				referenceCleaner.cleanupReferences(createdResourceWithResolvedReferences);
				eventManager.handleEvent(eventGenerator.newResourceCreatedEvent(createdResourceWithResolvedReferences));
			}
			catch (Exception e)
			{
				logger.warn("Error while handling resource created event", e);
			}

			BundleEntryComponent resultEntry = new BundleEntryComponent();
			resultEntry.setFullUrl(new IdType(serverBase, createdResource.getResourceType().name(),
					createdResource.getIdElement().getIdPart(), null).getValue());
			BundleEntryResponseComponent response = resultEntry.getResponse();
			response.setStatus(Status.CREATED.getStatusCode() + " " + Status.CREATED.getReasonPhrase());
			response.setLocation(createdResource.getIdElement()
					.withServerBase(serverBase, createdResource.getResourceType().name()).getValue());
			response.setEtag(new EntityTag(createdResource.getMeta().getVersionId(), true).toString());
			response.setLastModified(createdResource.getMeta().getLastUpdated());

			return Optional.of(resultEntry);
		}
		else
		{
			BundleEntryComponent resultEntry = new BundleEntryComponent();
			BundleEntryResponseComponent response = resultEntry.getResponse();
			response.setStatus(responseResult.getStatusInfo().getStatusCode() + " "
					+ responseResult.getStatusInfo().getReasonPhrase());

			if (responseResult.getLocation() != null)
				response.setLocation(responseResult.getLocation().toString());
			if (responseResult.getEntityTag() != null)
				response.setEtag(responseResult.getEntityTag().getValue());
			if (responseResult.getLastModified() != null)
				response.setLastModified(responseResult.getLastModified());

			return Optional.of(resultEntry);
		}
	}

	private R latestOrErrorIfDeletedOrNotFound(Connection connection, Resource resource) throws Exception
	{
		return dao
				.readWithTransaction(connection,
						parameterConverter.toUuid(resource.getResourceType().name(),
								resource.getIdElement().getIdPart()))
				.orElseThrow(() -> new ResourceNotFoundException(resource.getIdElement().getIdPart()));
	}
}
