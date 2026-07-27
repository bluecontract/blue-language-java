package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.contracts.CutOffProbeContractProcessor;
import blue.language.processor.contracts.MutateEmbeddedPathsContractProcessor;
import blue.language.processor.contracts.RemoveIfPresentContractProcessor;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.contracts.SetPropertyOnEventContractProcessor;
import blue.language.processor.model.TestEvent;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessEmbeddedTest {

    @Test
    void initializesEmbeddedChildDocument() {
        String yaml = "name: Sample Doc\n" +
                "x:\n" +
                "  name: Sample Sub Doc\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "      type:\n" +
                "        blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "      propertyKey: /a\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /x\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        Node original = blue.yamlToNode(yaml);
        DocumentProcessingResult result = blue.initializeDocument(original);
        Node initialized = result.document();

        Node child = initialized.getProperties().get("x");
        assertNotNull(child, "Embedded child should remain present");
        Node childContracts = child.getContracts();
        assertNotNull(childContracts, "Child contracts map should exist");
        assertTrue(childContracts.getProperties().containsKey("initialized"),
                "Child scope must record Initialization Marker");
        Node childMarker = childContracts.getProperties().get("initialized");
        Node childMarkerDocId = childMarker.getProperties().get("documentId");
        assertNotNull(childMarkerDocId);
        assertNotNull(childMarkerDocId.getValue());
        assertEquals(new BigInteger("1"), child.getProperties().get("a").getValue(),
                "Child property /x/a should be set by embedded handler");

        Node rootContracts = initialized.getContracts();
        assertNotNull(rootContracts, "Root contracts map should exist");
        assertTrue(rootContracts.getProperties().containsKey("initialized"),
                "Root scope must record Initialization Marker");
        Node rootMarker = rootContracts.getProperties().get("initialized");
        Node rootMarkerDocId = rootMarker.getProperties().get("documentId");
        assertNotNull(rootMarkerDocId);
        assertNotNull(rootMarkerDocId.getValue());
        assertFalse(rootMarkerDocId.getValue().equals(childMarkerDocId.getValue()));

        assertTrue(result.events().isEmpty(),
                "processor-generated initialization lifecycle is local");
    }

    @Test
    void rootScopeCannotModifyEmbeddedInterior() {
        String allowedYaml = "name: Sample Doc\n" +
                "x:\n" +
                "  name: Sample Sub Doc\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "      type:\n" +
                "        blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "      propertyKey: /a\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  rootLife:\n" +
                "    type:\n" +
                "      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /x\n" +
                "  setRootY:\n" +
                "    channel: rootLife\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /y\n" +
                "    propertyValue: 1\n";

        String forbiddenYaml = allowedYaml +
                "  setChildInterior:\n" +
                "    order: 1\n" +
                "    channel: rootLife\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /x/b\n" +
                "    propertyValue: 1\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());

        Node allowed = blue.yamlToNode(allowedYaml);
        DocumentProcessingResult allowedResult = blue.initializeDocument(allowed);
        Node initializedAllowed = allowedResult.document();
        assertEquals(new BigInteger("1"), initializedAllowed.getProperties().get("y").getValue());

        Node forbidden = blue.yamlToNode(forbiddenYaml);
        DocumentProcessingResult forbiddenResult = blue.initializeDocument(forbidden);
        assertRolledBack(forbidden, forbiddenResult);
    }

    @Test
    void nestedEmbeddedScopesEnforceBoundaries() {
        String nestedYaml = "name: Nested Doc\n" +
                "x:\n" +
                "  name: X Doc\n" +
                "  y:\n" +
                "    name: Y Doc\n" +
                "    contracts:\n" +
                "      life:\n" +
                "        type:\n" +
                "          blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "      setY:\n" +
                "        channel: life\n" +
                "        event:\n" +
                "          type:\n" +
                "            blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "        type:\n" +
                "          blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "        propertyKey: /a\n" +
                "        propertyValue: 1\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "    embedded:\n" +
                "      type:\n" +
                "        blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "      paths:\n" +
                "        - /y\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /x\n" +
                "  life:\n" +
                "    type:\n" +
                "      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n";

        String rootViolationYaml = nestedYaml +
                "  setDeep:\n" +
                "    channel: life\n" +
                "    event:\n" +
                "      type:\n" +
                "        blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /x/y/a\n" +
                "    propertyValue: 2\n";

        String parentScopeViolationYaml = "name: Nested Doc\n" +
                "x:\n" +
                "  name: X Doc\n" +
                "  y:\n" +
                "    name: Y Doc\n" +
                "    contracts:\n" +
                "      life:\n" +
                "        type:\n" +
                "          blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "      setY:\n" +
                "        channel: life\n" +
                "        event:\n" +
                "          type:\n" +
                "            blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "        type:\n" +
                "          blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "        propertyKey: /a\n" +
                "        propertyValue: 1\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "    embedded:\n" +
                "      type:\n" +
                "        blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "      paths:\n" +
                "        - /y\n" +
                "    setIllegalFromX:\n" +
                "      channel: life\n" +
                "      order: 1\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "      type:\n" +
                "        blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "      propertyKey: /y/a\n" +
                "      propertyValue: 2\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /x\n" +
                "  life:\n" +
                "    type:\n" +
                "      blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());

        Node nested = blue.yamlToNode(nestedYaml);
        DocumentProcessingResult nestedResult = blue.initializeDocument(nested);
        Node initialized = nestedResult.document();

        Node xNode = initialized.getProperties().get("x");
        assertNotNull(xNode);
        Node xContracts = xNode.getContracts();
        assertNotNull(xContracts);
        assertTrue(xContracts.getProperties().containsKey("initialized"));

        Node yNode = xNode.getProperties().get("y");
        assertNotNull(yNode);
        Node yContracts = yNode.getContracts();
        assertNotNull(yContracts);
        assertTrue(yContracts.getProperties().containsKey("initialized"));
        assertEquals(new BigInteger("1"), yNode.getProperties().get("a").getValue());

        Node originalY = nested.getProperties().get("x").getProperties().get("y");
        assertNull(originalY.getProperties() != null ? originalY.getProperties().get("a") : null);

        Node rootViolation = blue.yamlToNode(rootViolationYaml);
        DocumentProcessingResult rootResult = blue.initializeDocument(rootViolation);
        assertRolledBack(rootViolation, rootResult);

        Node parentScopeViolation = blue.yamlToNode(parentScopeViolationYaml);
        DocumentProcessingResult parentResult = blue.initializeDocument(parentScopeViolation);
        assertRolledBack(parentScopeViolation, parentResult);
    }

    @Test
    void embeddedListUpdatesProcessNewChildAfterCurrentScopeFinishes() {
        String yaml = "name: Sample Doc\n" +
                "a:\n" +
                "  name: Doc A\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "      type:\n" +
                "        blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "b:\n" +
                "  name: Doc B\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "      type:\n" +
                "        blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "c:\n" +
                "  name: Doc C\n" +
                "  contracts:\n" +
                "    life:\n" +
                "      type:\n" +
                "        blueId: 2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo\n" +
                "    setX:\n" +
                "      channel: life\n" +
                "      event:\n" +
                "        type:\n" +
                "          blueId: D22KJkwmKNhTXK3nPRdamypvnEAzaG3VAXJgFwHbLUQt\n" +
                "      type:\n" +
                "        blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /a\n" +
                "      - /b\n" +
                "  updateA:\n" +
                "    type:\n" +
                "      blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "    path: /a/x\n" +
                "  handleA:\n" +
                "    channel: updateA\n" +
                "    type:\n" +
                "      blueId: AYLVESeD9WrEegNra57vKC2RT65VCBqTz5n9f5MieEkA\n" +
                "  updateB:\n" +
                "    type:\n" +
                "      blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "    path: /b/x\n" +
                "  flagB:\n" +
                "    channel: updateB\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /mustNotHappen\n" +
                "    propertyValue: 1\n" +
                "  updateC:\n" +
                "    type:\n" +
                "      blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "    path: /c/x\n" +
                "  flagC:\n" +
                "    channel: updateC\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /itShouldHappen\n" +
                "    propertyValue: 1\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new MutateEmbeddedPathsContractProcessor());

        Node original = blue.yamlToNode(yaml);
        DocumentProcessingResult result = blue.initializeDocument(original);
        Node document = result.document();
        Node rootTerminated = terminatedMarker(document, "/");
        assertNull(rootTerminated);
    }

    @Test
    void embeddedListUpdatesAffectOnlyLaterExternalEvents() {
        String yaml = "name: Sample Doc\n" +
                "a:\n" +
                "  name: Doc A\n" +
                "  contracts:\n" +
                "    testEvents:\n" +
                "      type:\n" +
                "        blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "    setX:\n" +
                "      channel: testEvents\n" +
                "      type:\n" +
                "        blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "b:\n" +
                "  name: Doc B\n" +
                "  contracts:\n" +
                "    testEvents:\n" +
                "      type:\n" +
                "        blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "    setX:\n" +
                "      channel: testEvents\n" +
                "      type:\n" +
                "        blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "c:\n" +
                "  name: Doc C\n" +
                "  contracts:\n" +
                "    testEvents:\n" +
                "      type:\n" +
                "        blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "    setX:\n" +
                "      channel: testEvents\n" +
                "      type:\n" +
                "        blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "      propertyKey: /x\n" +
                "      propertyValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /a\n" +
                "      - /b\n" +
                "  updateA:\n" +
                "    type:\n" +
                "      blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "    path: /a/x\n" +
                "  mutatePaths:\n" +
                "    channel: updateA\n" +
                "    type:\n" +
                "      blueId: AYLVESeD9WrEegNra57vKC2RT65VCBqTz5n9f5MieEkA\n" +
                "  updateB:\n" +
                "    type:\n" +
                "      blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "    path: /b/x\n" +
                "  flagB:\n" +
                "    channel: updateB\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /mustNotHappen\n" +
                "    propertyValue: 1\n" +
                "  updateC:\n" +
                "    type:\n" +
                "      blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "    path: /c/x\n" +
                "  flagC:\n" +
                "    channel: updateC\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /itShouldHappen\n" +
                "    propertyValue: 1\n";

        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new SetPropertyContractProcessor());
        blue.registerContractProcessor(new MutateEmbeddedPathsContractProcessor());
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport.testEventChannelProcessor());
        DocumentProcessorExactFeederSupport.install(blue);

        Node original = blue.yamlToNode(yaml);
        DocumentProcessingResult initResult = blue.initializeDocument(original);
        Node initialized = initResult.document();

        Node initialContracts = initialized.getContracts();
        Node initialEmbedded = initialContracts.getProperties().get("embedded");
        Node initialPaths = initialEmbedded.getProperties().get("paths");
        assertNotNull(initialPaths);
        assertEquals(2, initialPaths.getItems().size());
        assertEquals("/a", initialPaths.getItems().get(0).getValue());
        assertEquals("/b", initialPaths.getItems().get(1).getValue());
        assertNull(initialized.getProperties().get("itShouldHappen"));
        assertNull(initialized.getProperties().get("mustNotHappen"));

        Node firstEvent = blue.objectToNode(
                new TestEvent().eventId("evt-current-membership"));
        DocumentProcessingResult firstResult =
                blue.processDocument(initialized, firstEvent);
        Node afterFirst = firstResult.document();
        Node rootTerminated = terminatedMarker(afterFirst, "/");
        assertNull(rootTerminated);
        Node updatedPaths = afterFirst.getContracts()
                .getProperties().get("embedded")
                .getProperties().get("paths");
        assertEquals(1, updatedPaths.getItems().size());
        assertEquals("/c", updatedPaths.getItems().get(0).getValue());
        assertNull(afterFirst.getProperties().get("itShouldHappen"),
                "the new /c membership must not affect the current event");
        assertNull(afterFirst.getProperties().get("mustNotHappen"),
                "the removed /b scope cannot run after it is cut off");
        Node cAfterFirst = afterFirst.getProperties().get("c");
        assertTrue(cAfterFirst.getProperties() == null
                        || cAfterFirst.getProperties().get("x") == null,
                "the new /c membership must not execute until the next event");

        Node secondEvent = blue.objectToNode(
                new TestEvent().eventId("evt-later-membership"));
        DocumentProcessingResult secondResult =
                blue.processDocument(afterFirst, secondEvent);
        Node afterSecond = secondResult.document();
        assertEquals(new BigInteger("1"),
                afterSecond.getProperties().get("c")
                        .getProperties().get("x").getValue());
        assertNotNull(afterSecond.getProperties().get("itShouldHappen"),
                secondResult.status() + ": " + diagnosticMessage(secondResult)
                        + "\n" + blue.nodeToYaml(afterSecond));
    }

    @Test
    void actualBalloonCutOffStillStopsFurtherEffects() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport.testEventChannelProcessor());
        blue.registerContractProcessor(new CutOffProbeContractProcessor());
        blue.registerContractProcessor(new RemoveIfPresentContractProcessor());
        blue.registerContractProcessor(new SetPropertyOnEventContractProcessor());
        DocumentProcessorExactFeederSupport.install(blue);

        String yaml = "child:\n" +
                "  contracts:\n" +
                "    childChannel:\n" +
                "      type:\n" +
                "        blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "    probe:\n" +
                "      channel: childChannel\n" +
                "      type:\n" +
                "        blueId: A8kbVbinjJAPFnbaQgBRCDU6h64xydTHe69kPakvgjbU\n" +
                "      emitBefore: true\n" +
                "      preEmitKind: pre\n" +
                "      patchPointer: /marker\n" +
                "      patchValue: 1\n" +
                "      emitAfter: true\n" +
                "      postEmitKind: post\n" +
                "      postPatchPointer: /resurrection\n" +
                "      postPatchValue: 1\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /child\n" +
                "  embeddedBridge:\n" +
                "    type:\n" +
                "      blueId: 7ZgUJxCyokHf84uibaQz138mFRLarykWLewVAn8bibTN\n" +
                "    sourcePath: /child\n" +
                "  bridgePre:\n" +
                "    channel: embeddedBridge\n" +
                "    type:\n" +
                "      blueId: H1qKGon7JWgUU9P8oUiHjxoR5hWbkAzVWWNukXf4cHz\n" +
                "    expectedKind: pre\n" +
                "    propertyKey: /bridged\n" +
                "    propertyValue: 1\n" +
                "  bridgePost:\n" +
                "    channel: embeddedBridge\n" +
                "    type:\n" +
                "      blueId: H1qKGon7JWgUU9P8oUiHjxoR5hWbkAzVWWNukXf4cHz\n" +
                "    expectedKind: post\n" +
                "    propertyKey: /postSeen\n" +
                "    propertyValue: 1\n" +
                "  childUpdates:\n" +
                "    type:\n" +
                "      blueId: 4qgDZkkhfL8FLHLWH711pwPBSJ49SnicutmRXF1RB6An\n" +
                "    path: /child\n" +
                "  cutChild:\n" +
                "    channel: childUpdates\n" +
                "    type:\n" +
                "      blueId: 72r7LSWk5VP9Wh1e5KJX2x8Mrr7Yk8d8Zey9QTbDaHBe\n" +
                "    propertyKey: /child\n";

        Node source = blue.yamlToNode(yaml);
        Node initialized = blue.initializeDocument(source).document();

        Node event = blue.objectToNode(new TestEvent().eventId("evt-1"));
        DocumentProcessingResult result = blue.processDocument(initialized, event);
        Node processed = result.document();

        assertNull(processed.getProperties() != null ? processed.getProperties().get("child") : null,
                "Child scope should remain removed after cut-off; status="
                        + result.status() + ", reason="
                        + diagnosticMessage(result) + "\n"
                        + blue.nodeToYaml(processed));

        assertNull(processed.getProperties() != null ? processed.getProperties().get("postSeen") : null,
                "No post-cut-off emission should be bridged");

        boolean postEmissionRecorded = result.events().stream()
                .map(Node::getProperties)
                .filter(props -> props != null && props.get("kind") != null)
                .anyMatch(props -> "post".equals(props.get("kind").getValue()));
        assertFalse(postEmissionRecorded, "Post-cut-off emission must not reach root events");
    }

    @Test
    void embeddedPathSlashFailsAtomicallyWithoutACommittedTerminationMarker() {
        String yaml = "name: Self Embedded\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /\n";

        Blue blue = ProcessorTestSupport.blue();
        Node input = blue.yamlToNode(yaml);
        DocumentProcessingResult result = blue.initializeDocument(input);

        assertEquals(ProcessorStatus.CAPABILITY_FAILURE,
                result.status(), diagnosticMessage(result));
        assertEquals(ProcessorErrorCategory.PatchBoundaryViolation,
                diagnosticCategory(result), diagnosticMessage(result));
        assertFalse(result.commits());
        assertTrue(result.events().isEmpty());
        assertEquals(input.toString(), result.document().toString());
        assertNull(terminatedMarker(result.document(), "/"));
    }

    @Test
    void duplicateEmbeddedPathsAreRejected() {
        String yaml = "name: Duplicate Embedded\n" +
                "child:\n" +
                "  name: Child\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /child\n" +
                "      - /child\n";

        Blue blue = ProcessorTestSupport.blue();
        Node input = blue.yamlToNode(yaml);
        DocumentProcessingResult result = blue.initializeDocument(input);

        assertTrue(isCapabilityFailure(result));
        assertTrue(diagnosticMessage(result).contains("Unique items"));
        assertEquals(input.toString(), result.document().toString());
    }

    @Test
    void embeddedPathSelectingNonObjectFailsAtomically() {
        String yaml = "name: Scalar Embedded\n" +
                "child: scalar\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /child\n";

        Blue blue = ProcessorTestSupport.blue();
        Node input = blue.yamlToNode(yaml);
        DocumentProcessingResult result = blue.initializeDocument(input);

        assertRolledBack(input, result);
    }

    @Test
    void embeddedPathSelectingPureReferenceIsBoundaryViolationBeforeInitialization() {
        Node childType = new Node()
                .name("Referenced Embedded Context Type")
                .properties("inherited", new Node().value("forces typed materialization"));
        Node referenced = new Node()
                .name("Referenced Object Is Not A Selected Object Node")
                .properties("payload", new Node().value("provider content"));
        BasicNodeProvider provider = new BasicNodeProvider(childType, referenced);
        String childTypeBlueId = provider.getBlueIdByName(childType.getName());
        Node parentType = new Node()
                .name("Referenced Embedded Parent Type")
                .properties("child", new Node().type(new Node().blueId(childTypeBlueId)));
        provider.addSingleNodes(parentType);
        String parentTypeBlueId = provider.getBlueIdByName(parentType.getName());
        String referencedBlueId = provider.getBlueIdByName(referenced.getName());
        String yaml = "name: Referenced Embedded\n" +
                "type:\n" +
                "  blueId: " + parentTypeBlueId + "\n" +
                "child:\n" +
                "  blueId: " + referencedBlueId + "\n" +
                "contracts:\n" +
                "  embedded:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESS_EMBEDDED + "\n" +
                "    paths:\n" +
                "      - /child\n";

        Blue blue = ProcessorTestSupport.blue(provider);
        DocumentProcessingResult result = blue.initializeDocument(blue.yamlToNode(yaml));

        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(), diagnosticMessage(result));
        assertEquals(ProcessorErrorCategory.PatchBoundaryViolation,
                diagnosticCategory(result), diagnosticMessage(result));
        assertTrue(result.document().getProperties().get("child").isReferenceOnly(),
                "the referenced child must not be initialized or mutated as an active scope");
        assertTrue(result.events().isEmpty());
        assertFalse(result.commits());
    }

    @Test
    void rejectsMultipleProcessEmbeddedMarkersWithinScope() {
        String yaml = "name: Multi Embedded Doc\n" +
                "x:\n" +
                "  name: X Doc\n" +
                "y:\n" +
                "  name: Y Doc\n" +
                "contracts:\n" +
                "  embeddedPrimary:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /x\n" +
                "  embeddedSecondary:\n" +
                "    type:\n" +
                "      blueId: D5s6GcGwW2hwqy4SrzUuxzdPPRNZ3jNuDkFHbUDmnHZr\n" +
                "    paths:\n" +
                "      - /y\n";

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);

        DocumentProcessingResult result = blue.initializeDocument(document);
        assertEquals(ProcessorStatus.CAPABILITY_FAILURE,
                result.status(), diagnosticMessage(result));
        assertEquals(ProcessorErrorCategory.PatchBoundaryViolation,
                diagnosticCategory(result), diagnosticMessage(result));
        assertTrue(diagnosticMessage(result).contains("Process Embedded"));
        assertFalse(result.commits());
        assertTrue(result.events().isEmpty());
        assertEquals(document.toString(), result.document().toString());
    }

    private void assertRolledBack(Node input, DocumentProcessingResult result) {
        assertEquals(ProcessorStatus.RUNTIME_FATAL,
                result.status(), diagnosticMessage(result));
        assertFalse(result.commits());
        assertTrue(result.events().isEmpty());
        assertEquals(input.toString(), result.document().toString());
        assertNull(terminatedMarker(result.document(), "/"));
    }

    private Node terminatedMarker(Node document, String scopePath) {
        String contractsPointer = ProcessorEngine.resolvePointer(scopePath, "/contracts");
        try {
            Node contracts = document.getAsNode(contractsPointer);
            if (contracts == null || contracts.getProperties() == null) {
                return null;
            }
            return contracts.getProperties().get("terminated");
        } catch (Exception ex) {
            return null;
        }
    }
}
