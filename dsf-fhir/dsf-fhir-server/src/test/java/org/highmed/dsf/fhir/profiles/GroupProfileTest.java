package org.highmed.dsf.fhir.profiles;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.highmed.dsf.fhir.service.ResourceValidator;
import org.highmed.dsf.fhir.service.ResourceValidatorImpl;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Expression;
import org.hl7.fhir.r4.model.Group;
import org.hl7.fhir.r4.model.Group.GroupType;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.ValidationResult;

public class GroupProfileTest
{
	private static final Logger logger = LoggerFactory.getLogger(GroupProfileTest.class);

	@ClassRule
	public static final ValidationSupportRule validationRule = new ValidationSupportRule(
			Arrays.asList("highmed-extension-query-0.3.0.xml", "highmed-group-0.3.0.xml"),
			Arrays.asList("authorization-role-0.3.0.xml", "query-type-0.3.0.xml"),
			Arrays.asList("authorization-role-0.3.0.xml", "query-type-0.3.0.xml"));

	private ResourceValidator resourceValidator = new ResourceValidatorImpl(validationRule.getFhirContext(),
			validationRule.getValidationSupport());

	@Test
	public void testGroupProfileValid() throws Exception
	{
		Group group = new Group();
		group.getMeta().addProfile("http://highmed.org/fhir/StructureDefinition/highmed-group");
		group.setType(GroupType.PERSON);
		group.setActual(false);
		group.addExtension().setUrl("http://highmed.org/fhir/StructureDefinition/query")
				.setValue(new Expression()
						.setLanguageElement(new CodeType("application/x-aql-query")
								.setSystem("http://highmed.org/fhir/CodeSystem/query-type"))
						.setExpression("SELECT COUNT(e) FROM EHR e"));

		ValidationResult result = resourceValidator.validate(group);
		result.getMessages().stream().map(m -> m.getLocationString() + " " + m.getLocationLine() + ":"
				+ m.getLocationCol() + " - " + m.getSeverity() + ": " + m.getMessage()).forEach(logger::info);

		assertEquals(0, result.getMessages().stream().filter(m -> ResultSeverityEnum.ERROR.equals(m.getSeverity())
				|| ResultSeverityEnum.FATAL.equals(m.getSeverity())).count());
	}
}
