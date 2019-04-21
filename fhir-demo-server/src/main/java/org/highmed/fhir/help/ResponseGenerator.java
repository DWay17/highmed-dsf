package org.highmed.fhir.help;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;

import org.highmed.fhir.dao.command.ResourceReference;
import org.highmed.fhir.search.PartialResult;
import org.highmed.fhir.search.SearchQueryParameterError;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.SearchEntryMode;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.UriType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResponseGenerator
{
	private static final Logger logger = LoggerFactory.getLogger(ResponseGenerator.class);

	private final String serverBase;

	public ResponseGenerator(String serverBase)
	{
		this.serverBase = serverBase;
	}

	public OperationOutcome createOutcome(IssueSeverity severity, IssueType type, String diagnostics)
	{
		OperationOutcome outcome = new OperationOutcome();
		outcome.getIssueFirstRep().setSeverity(severity);
		outcome.getIssueFirstRep().setCode(type);
		outcome.getIssueFirstRep().setDiagnostics(diagnostics);
		return outcome;
	}

	public ResponseBuilder response(Status status, Resource resource, MediaType mediaType)
	{
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(resource, "resource");

		ResponseBuilder b = Response.status(status).entity(resource);

		if (mediaType != null)
			b = b.type(mediaType);

		if (resource.getMeta() != null && resource.getMeta().getLastUpdated() != null
				&& resource.getMeta().getVersionId() != null)
		{
			b = b.lastModified(resource.getMeta().getLastUpdated());
			b = b.tag(new EntityTag(resource.getMeta().getVersionId(), true));
		}

		return b;
	}

	public BundleEntryComponent toBundleEntryComponent(DomainResource resource, SearchEntryMode mode)
	{
		BundleEntryComponent entry = new BundleEntryComponent();
		entry.getSearch().setMode(mode);
		entry.setResource(resource);
		entry.setFullUrlElement(new IdType(serverBase, resource.getIdElement().getResourceType(),
				resource.getIdElement().getIdPart(), null));
		return entry;
	}

	public Bundle createSearchSet(PartialResult<? extends DomainResource> result,
			List<SearchQueryParameterError> errors, UriBuilder bundleUri, String format, String pretty)
	{
		Bundle bundle = new Bundle();
		bundle.setTimestamp(new Date());
		bundle.setType(BundleType.SEARCHSET);
		result.getPartialResult().stream().map(r -> toBundleEntryComponent(r, SearchEntryMode.MATCH))
				.forEach(bundle::addEntry);
		result.getIncludes().stream().map(r -> toBundleEntryComponent(r, SearchEntryMode.INCLUDE))
				.forEach(bundle::addEntry);
		if (!errors.isEmpty())
			bundle.addEntry(toBundleEntryComponent(toOperationOutcome(errors), SearchEntryMode.OUTCOME));

		bundle.setTotal(result.getOverallCount());

		if (format != null)
			bundleUri = bundleUri.replaceQueryParam("_format", format);
		if (pretty != null)
			bundleUri = bundleUri.replaceQueryParam("_pretty", pretty);

		if (result.getPageAndCount().getCount() > 0)
		{
			bundleUri = bundleUri.replaceQueryParam("_count", result.getPageAndCount().getCount());
			bundleUri = bundleUri.replaceQueryParam("_page",
					result.getPartialResult().isEmpty() ? 1 : result.getPageAndCount().getPage());
		}
		else
			bundleUri = bundleUri.replaceQueryParam("_count", "0");

		bundle.addLink().setRelation("self").setUrlElement(new UriType(bundleUri.build()));

		if (result.getPageAndCount().getCount() > 0 && !result.getPartialResult().isEmpty())
		{
			bundleUri = bundleUri.replaceQueryParam("_page", 1);
			bundleUri = bundleUri.replaceQueryParam("_count", result.getPageAndCount().getCount());
			bundle.addLink().setRelation("first").setUrlElement(new UriType(bundleUri.build()));

			if (result.getPageAndCount().getPage() > 1)
			{
				bundleUri = bundleUri.replaceQueryParam("_page", result.getPageAndCount().getPage() - 1);
				bundleUri = bundleUri.replaceQueryParam("_count", result.getPageAndCount().getCount());
				bundle.addLink().setRelation("previous").setUrlElement(new UriType(bundleUri.build()));
			}
			if (!result.isLastPage())
			{
				bundleUri = bundleUri.replaceQueryParam("_page", result.getPageAndCount().getPage() + 1);
				bundleUri = bundleUri.replaceQueryParam("_count", result.getPageAndCount().getCount());
				bundle.addLink().setRelation("next").setUrlElement(new UriType(bundleUri.build()));
			}

			bundleUri = bundleUri.replaceQueryParam("_page", result.getLastPage());
			bundleUri = bundleUri.replaceQueryParam("_count", result.getPageAndCount().getCount());
			bundle.addLink().setRelation("last").setUrlElement(new UriType(bundleUri.build()));
		}

		return bundle;
	}

	public OperationOutcome toOperationOutcome(List<SearchQueryParameterError> errors)
	{
		String diagnostics = errors.stream().map(SearchQueryParameterError::toString).collect(Collectors.joining("; "));
		return createOutcome(IssueSeverity.WARNING, IssueType.PROCESSING, diagnostics);
	}

	public Response createPathVsElementIdResponse(String resourceTypeName, String id, IdType resourceId)
	{
		OperationOutcome out = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				"Path id not equal to " + resourceTypeName + " id (" + id + " vs. " + resourceId.getIdPart() + ").");
		return Response.status(Status.BAD_REQUEST).entity(out).build();
	}

	public Response createInvalidBaseUrlResponse(String resourceTypeName, IdType resourceId)
	{
		OperationOutcome out = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				resourceTypeName + " id.baseUrl must be null or equal to " + serverBase + ", value "
						+ resourceId.getBaseUrl() + " unexpected.");
		return Response.status(Status.BAD_REQUEST).entity(out).build();
	}

	public Response badRequest(String queryParameters, List<SearchQueryParameterError> unsupportedQueryParameters)
	{
		String unsupportedQueryParametersString = unsupportedQueryParameters.stream()
				.map(SearchQueryParameterError::toString).collect(Collectors.joining("; "));
		logger.error("Bad request '{}', unsupported query parameter{} {}", queryParameters,
				unsupportedQueryParameters.size() != 1 ? "s" : "", unsupportedQueryParametersString);

		OperationOutcome outcome = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				"Bad request '" + queryParameters + "', unsupported query parameter"
						+ (unsupportedQueryParameters.size() != 1 ? "s" : "") + " " + unsupportedQueryParametersString);
		return Response.status(Status.BAD_REQUEST).entity(outcome).build();
	}

	public Response badRequestIdsNotMatching(IdType dbResourceId, IdType resourceId)
	{
		logger.error("Bad request Id {} does not match db Id {}", resourceId.getValue(), dbResourceId.getValue());

		OperationOutcome outcome = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				"Bad request Id " + resourceId.getValue() + " does not match db Id " + dbResourceId.getValue());
		return Response.status(Status.BAD_REQUEST).entity(outcome).build();
	}

	public Response updateAsCreateNotAllowed(String resourceTypeName, String id)
	{
		logger.error("{} with id {} not found", resourceTypeName, id);

		OperationOutcome outcome = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				"Resource with id " + id + " not found");
		return Response.status(Status.METHOD_NOT_ALLOWED).entity(outcome).build();
	}

	public Response multipleExists(String resourceTypeName, String ifNoneExistsHeaderValue)
	{
		logger.error("Multiple {} resources with criteria {} exist", resourceTypeName, ifNoneExistsHeaderValue);

		OperationOutcome outcome = createOutcome(IssueSeverity.ERROR, IssueType.MULTIPLEMATCHES,
				"Multiple " + resourceTypeName + " resources with criteria '" + ifNoneExistsHeaderValue + "' exist");
		return Response.status(Status.PRECONDITION_FAILED).entity(outcome).build();
	}

	public Response badIfNoneExistHeaderValue(String ifNoneExistsHeaderValue)
	{
		logger.error("Bad If-None-Exist header value '{}'", ifNoneExistsHeaderValue);

		OperationOutcome outcome = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				"Bad If-None-Exist header value '" + ifNoneExistsHeaderValue + "'");
		return Response.status(Status.BAD_REQUEST).entity(outcome).build();
	}

	public Response badIfNoneExistHeaderValue(String ifNoneExistsHeaderValue,
			List<SearchQueryParameterError> unsupportedQueryParameters)
	{
		String unsupportedQueryParametersString = unsupportedQueryParameters.stream()
				.map(SearchQueryParameterError::toString).collect(Collectors.joining("; "));
		logger.error("Bad If-None-Exist header value '{}', unsupported query parameter{} {}", ifNoneExistsHeaderValue,
				unsupportedQueryParameters.size() != 1 ? "s" : "", unsupportedQueryParametersString);

		OperationOutcome outcome = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				"Bad If-None-Exist header value '" + ifNoneExistsHeaderValue + "', unsupported query parameter"
						+ (unsupportedQueryParameters.size() != 1 ? "s" : "") + " " + unsupportedQueryParametersString);
		return Response.status(Status.BAD_REQUEST).entity(outcome).build();
	}

	public Response oneExists(String resourceTypeName, String ifNoneExistsHeaderValue)
	{
		logger.info("{} with criteria {} exists", resourceTypeName, ifNoneExistsHeaderValue);

		OperationOutcome outcome = createOutcome(IssueSeverity.INFORMATION, IssueType.DUPLICATE,
				"Resource with criteria '" + ifNoneExistsHeaderValue + "' exists");

		return Response.status(Status.OK).entity(outcome).build();
	}

	public Response unknownReference(int bundleIndex, DomainResource resource, ResourceReference resourceReference)
	{
		logger.error("Unknown reference at {} in resource of type {} with id {} at bundle index {}",
				resourceReference.getReferenceLocation(), resource.getResourceType().name(), resource.getId(),
				bundleIndex);

		OperationOutcome outcome = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				"Unknown reference at " + resourceReference.getReferenceLocation() + " in resource of type "
						+ resource.getResourceType().name() + " with id " + resource.getId() + " at bundle index "
						+ bundleIndex);
		return Response.status(Status.BAD_REQUEST).entity(outcome).build();
	}

	public Response referenceTargetTypeNotSupportedByImplementation(int bundleIndex, DomainResource resource,
			ResourceReference resourceReference)
	{
		logger.error(
				"Reference target type of reference at {} in resource of type {} with id {} at bundle index {} not supported by this implementation",
				resourceReference.getReferenceLocation(), resource.getResourceType().name(), resource.getId(),
				bundleIndex);

		OperationOutcome outcome = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				"Reference target type of reference at " + resourceReference.getReferenceLocation()
						+ " in resource of type " + resource.getResourceType().name() + " with id " + resource.getId()
						+ " at bundle index " + bundleIndex + " not supported by this implementation");
		return Response.status(Status.BAD_REQUEST).entity(outcome).build();
	}

	public Response referenceTargetTypeNotSupportedByResource(int bundleIndex, DomainResource resource,
			ResourceReference resourceReference)
	{
		logger.error(
				"Reference target type of reference at {} in resource of type {} with id {} at bundle index {} not supported",
				resourceReference.getReferenceLocation(), resource.getResourceType().name(), resource.getId(),
				bundleIndex);

		OperationOutcome outcome = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				"Reference target type of reference at " + resourceReference.getReferenceLocation()
						+ " in resource of type " + resource.getResourceType().name() + " with id " + resource.getId()
						+ " at bundle index " + bundleIndex + " not supported");
		return Response.status(Status.BAD_REQUEST).entity(outcome).build();
	}

	public Response referenceTargetNotFoundLocally(int bundleIndex, DomainResource resource,
			ResourceReference resourceReference)
	{
		logger.error(
				"Reference target {} of reference at {} in resource of type {} with id {} at bundle index {} not found",
				resourceReference.getReference().getReference(), resourceReference.getReferenceLocation(),
				resource.getResourceType().name(), resource.getId(), bundleIndex);

		OperationOutcome outcome = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				"Reference target " + resourceReference.getReference().getReference() + " of reference at "
						+ resourceReference.getReferenceLocation() + " in resource of type "
						+ resource.getResourceType().name() + " with id " + resource.getId() + " at bundle index "
						+ bundleIndex + " not found");
		return Response.status(Status.BAD_REQUEST).entity(outcome).build();
	}

	public Response badReference(boolean logicalNoConditional, int bundleIndex, DomainResource resource,
			ResourceReference resourceReference, String queryParameters,
			List<SearchQueryParameterError> unsupportedQueryParameters)
	{
		String unsupportedQueryParametersString = unsupportedQueryParameters.stream()
				.map(SearchQueryParameterError::toString).collect(Collectors.joining("; "));

		logger.error(
				"{} reference {} at {} in resource of type {} with id {} at bundle index {} contains unsupported queryparameter{} {}",
				logicalNoConditional ? "Logical" : "Conditional", queryParameters,
				resourceReference.getReferenceLocation(), resource.getResourceType().name(), resource.getId(),
				bundleIndex, unsupportedQueryParameters.size() != 1 ? "s" : "", unsupportedQueryParametersString);

		OperationOutcome outcome = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				(logicalNoConditional ? "Logical" : "Conditional") + " reference " + queryParameters + " at "
						+ resourceReference.getReferenceLocation() + " in resource of type "
						+ resource.getResourceType().name() + " with id " + resource.getId() + " at bundle index "
						+ bundleIndex + " contains unsupported queryparameter"
						+ (unsupportedQueryParameters.size() != 1 ? "s" : "") + " " + unsupportedQueryParametersString);
		return Response.status(Status.BAD_REQUEST).entity(outcome).build();
	}

	public Response referenceTargetNotFoundLocallyByIdentifier(int bundleIndex, DomainResource resource,
			ResourceReference resourceReference)
	{
		logger.error(
				"Reference target by identifier '{}|{}' of reference at {} in resource of type {} with id {} at bundle index {} not found",
				resourceReference.getReference().getIdentifier().getSystem(),
				resourceReference.getReference().getIdentifier().getValue(), resourceReference.getReferenceLocation(),
				resource.getResourceType().name(), resource.getId(), bundleIndex);

		OperationOutcome outcome = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				"Reference target by identifier '" + resourceReference.getReference().getIdentifier().getSystem() + "|"
						+ resourceReference.getReference().getIdentifier().getValue() + "' of reference at "
						+ resourceReference.getReferenceLocation() + " in resource of type "
						+ resource.getResourceType().name() + " with id " + resource.getId() + " at bundle index "
						+ bundleIndex + " not found");
		return Response.status(Status.BAD_REQUEST).entity(outcome).build();
	}

	public Response referenceTargetMultipleMatchesLocallyByIdentifier(int bundleIndex, DomainResource resource,
			ResourceReference resourceReference, int overallCount)
	{
		logger.error(
				"Found {} matches for reference target by identifier '{}|{}' of reference at {} in resource of type {} with id {} at bundle index {}",
				overallCount, resourceReference.getReference().getIdentifier().getSystem(),
				resourceReference.getReference().getIdentifier().getValue(), resourceReference.getReferenceLocation(),
				resource.getResourceType().name(), resource.getId(), bundleIndex);

		OperationOutcome outcome = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				"Found " + overallCount + " matches for reference target by identifier '"
						+ resourceReference.getReference().getIdentifier().getSystem() + "|"
						+ resourceReference.getReference().getIdentifier().getValue() + "' of reference at "
						+ resourceReference.getReferenceLocation() + " in resource of type "
						+ resource.getResourceType().name() + " with id " + resource.getId() + " at bundle index "
						+ bundleIndex + " not found");
		return Response.status(Status.BAD_REQUEST).entity(outcome).build();
	}

	public Response referenceTargetNotFoundLocallyByCondition(int bundleIndex, DomainResource resource,
			ResourceReference resourceReference, String queryParameters)
	{
		logger.error(
				"Reference target by identifier '{}|{}' of reference at {} in resource of type {} with id {} at bundle index {} not found",
				resourceReference.getReference().getIdentifier().getSystem(),
				resourceReference.getReference().getIdentifier().getValue(), resourceReference.getReferenceLocation(),
				resource.getResourceType().name(), resource.getId(), bundleIndex);

		OperationOutcome outcome = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				"Reference target by identifier '" + resourceReference.getReference().getIdentifier().getSystem() + "|"
						+ resourceReference.getReference().getIdentifier().getValue() + "' of reference at "
						+ resourceReference.getReferenceLocation() + " in resource of type "
						+ resource.getResourceType().name() + " with id " + resource.getId() + " at bundle index "
						+ bundleIndex + " not found");
		return Response.status(Status.BAD_REQUEST).entity(outcome).build();
	}

	public Response referenceTargetMultipleMatchesLocallyByCondition(int bundleIndex, DomainResource resource,
			ResourceReference resourceReference, int overallCount, String queryParameters)
	{
		logger.error(
				"Found {} matches for reference target by condition '{}' of reference at {} in resource of type {} with id {} at bundle index {}",
				overallCount, queryParameters, resourceReference.getReferenceLocation(),
				resource.getResourceType().name(), resource.getId(), bundleIndex);

		OperationOutcome outcome = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				"Found " + overallCount + " matches for reference target by condition '" + queryParameters
						+ "' of reference at " + resourceReference.getReferenceLocation() + " in resource of type "
						+ resource.getResourceType().name() + " with id " + resource.getId() + " at bundle index "
						+ bundleIndex + " not found");
		return Response.status(Status.BAD_REQUEST).entity(outcome).build();
	}

	public Response referenceTargetBadCondition(int bundleIndex, DomainResource resource,
			ResourceReference resourceReference)
	{
		logger.error(
				"Bad conditional reference target '{}' of reference at {} in resource of type {} with id {} at bundle index {}",
				resourceReference.getReference().getReference(), resourceReference.getReferenceLocation(),
				resource.getResourceType().name(), resource.getId(), bundleIndex);

		OperationOutcome outcome = createOutcome(IssueSeverity.ERROR, IssueType.PROCESSING,
				"Bad conditional reference target '" + resourceReference.getReference().getReference()
						+ "' of reference at " + resourceReference.getReferenceLocation() + " in resource of type "
						+ resource.getResourceType().name() + " with id " + resource.getId() + " at bundle index "
						+ bundleIndex + " not found");
		return Response.status(Status.BAD_REQUEST).entity(outcome).build();
	}
}
