package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import static blue.language.utils.Properties.DICTIONARY_TYPE_BLUE_ID;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.*;

public class DocumentLinksResolveTest {

	@Test
	public void resolvesDocumentLinksDictionaries() throws Exception {
		BasicNodeProvider nodeProvider = new BasicNodeProvider();

		String linkYaml =
				"name: Link\n" +
				"description: 'Abstract base class for all link types.'\n" +
				"anchor:\n" +
				"  type:\n" +
				"    blueId: " + TEXT_TYPE_BLUE_ID + "\n" +
        "  description: 'Target anchor key on the destination document.'\n";
		nodeProvider.addSingleDocs(linkYaml);
		String linkBlueId = nodeProvider.getBlueIdByName("Link");

		String documentLinkYaml =
				"name: Document Link\n" +
				"type:\n" +
				"  blueId: " + linkBlueId + "\n" +
        "description: 'Link targeting a specific Blue document by its stable documentId (initial blueId before any processing). Used to point to a logical document regardless of session.'\n" +
        "documentId:\n" +
				"  type:\n" +
				"    blueId: " + TEXT_TYPE_BLUE_ID + "\n" +
        "  description: 'Stable document identifier (original BlueId) of the target document.'\n";
		nodeProvider.addSingleDocs(documentLinkYaml);

    System.out.println(		nodeProvider.getBlueIdByName("Document Link"));

		String documentLinksYaml =
				"name: Document Links\n" +
        "description: 'Dictionary of named outgoing connections from this document to anchors on other documents or sessions. MyOS indexes supported link variants to power discovery.'\n" +
				"type:\n" +
				"  blueId: " + DICTIONARY_TYPE_BLUE_ID + "\n" +
				"keyType:\n" +
				"  blueId: " + TEXT_TYPE_BLUE_ID + "\n" +
				"valueType:\n" +
				"  blueId: " + linkBlueId + "\n";
		nodeProvider.addSingleDocs(documentLinksYaml);

		Blue blue = new Blue(nodeProvider);

		String yaml =
				"contracts:\n" +
				"  links:\n" +
				"    type:\n" +
				"      blueId: " + nodeProvider.getBlueIdByName("Document Links") + "\n" +
				"    link1:\n" +
				"      type:\n" +
				"        blueId: " + nodeProvider.getBlueIdByName("Document Link") + "\n" +
				"      anchor: anchorA\n" +
				"      documentId: doc-123\n";

		Node node = blue.yamlToNode(yaml);
		assertNotNull(node);

		Node resolved = blue.resolve(node);
		assertNotNull(resolved);

		Node contracts = resolved.getProperties().get("contracts");
		assertNotNull(contracts);

		Node linksNode = contracts.getProperties().get("links");
		assertNotNull(linksNode);
		assertEquals("Link", linksNode.getValueType().getName());

		Node link1 = linksNode.getProperties().get("link1");
		assertNotNull(link1);
		assertEquals("Document Link", link1.getType().getName());
		assertEquals("doc-123", link1.getProperties().get("documentId").getValue());
	}
}


