package org.opengis.cite.iso19142.transaction;

import com.sun.jersey.api.client.ClientResponse;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import javax.xml.namespace.QName;
import javax.xml.transform.dom.DOMSource;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import org.opengis.cite.iso19142.ETSAssert;
import org.opengis.cite.iso19142.ErrorMessage;
import org.opengis.cite.iso19142.ErrorMessageKeys;
import org.opengis.cite.iso19142.Namespaces;
import org.opengis.cite.iso19142.ProtocolBinding;
import org.opengis.cite.iso19142.WFS2;
import org.opengis.cite.iso19142.util.XMLUtils;
import org.opengis.cite.iso19142.util.ServiceMetadataUtils;
import org.opengis.cite.iso19142.util.TestSuiteLogger;
import org.opengis.cite.iso19142.util.WFSRequest;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Tests the response to a Transaction request that includes one or more insert
 * actions.
 * 
 * @see "ISO 19142:2010, cl. 15.2.4: Insert action"
 */
public class InsertTests extends TransactionFixture {

    private Map<String, QName> createdFeatures = new HashMap<String, QName>();

    /**
     * Restores the WFS data store to its previous state by deleting all
     * features that were successfully inserted by test methods in this class.
     */
    @AfterClass
    public void removeNewFeatures() {
        if (createdFeatures.isEmpty()) {
            return;
        }
        Document rspEntity = this.wfsClient.delete(createdFeatures,
                ProtocolBinding.ANY);
        String xpath = String.format("//wfs:totalDeleted = '%d'",
                createdFeatures.size());
        Boolean result;
        try {
            result = (Boolean) XMLUtils.evaluateXPath(rspEntity, xpath, null,
                    XPathConstants.BOOLEAN);
        } catch (XPathExpressionException xpe) {
            throw new RuntimeException(xpe);
        }
        if (!result) {
            String msg = String.format(
                    "%s: Failed to remove all new features:\n %s \n%s",
                    getClass().getName(), this.createdFeatures,
                    XMLUtils.writeNodeToString(rspEntity));
            TestSuiteLogger.log(Level.WARNING, msg);
        }
    }

    /**
     * [{@code Test}] Submits a Transaction request to insert a feature instance
     * of a type supported by the SUT. The test is run for all supported
     * Transaction request bindings and feature types. The response entity
     * (wfs:TransactionResponse) must be schema-valid and contain the
     * wfs:InsertResults element.
     * 
     * @param binding
     *            A supported message binding.
     * @param featureType
     *            A QName representing the qualified name of some feature type.
     * 
     * @see "ISO 19142:2010, cl. 15.3.3: TransactionSummary element"
     * @see "ISO 19142:2010, cl. 15.3.4: InsertResults element"
     */
    @Test(dataProvider = "binding+availFeatureType")
    public void insertSupportedFeature(ProtocolBinding binding,
            QName featureType) {
        Node feature = createFeatureInstance(featureType);
        WFSRequest.addInsertStatement(this.reqEntity, feature);
        URI endpoint = ServiceMetadataUtils.getOperationEndpoint(
                this.wfsMetadata, WFS2.TRANSACTION, binding);
        ClientResponse rsp = this.wfsClient.submitRequest(new DOMSource(
                this.reqEntity), binding, endpoint);
        this.rspEntity = rsp.getEntity(Document.class);
        Assert.assertEquals(rsp.getStatus(),
                ClientResponse.Status.OK.getStatusCode(),
                ErrorMessage.get(ErrorMessageKeys.UNEXPECTED_STATUS));
        ETSAssert.assertXPath("//wfs:TransactionResponse/wfs:InsertResults",
                this.rspEntity, null);
        List<String> newFeatureIDs = extractFeatureIdentifiers(this.rspEntity);
        String id = newFeatureIDs.get(0);
        createdFeatures.put(id, featureType);
        ETSAssert.assertFeatureAvailability(id, true, this.wfsClient);
    }

    /**
     * [{@code Test}] Submits a Transaction request to insert a feature instance
     * of a type not recognized by the SUT. An ExceptionReport (with status code
     * 400) containing the exception code {@code InvalidValue} is expected in
     * response.
     * 
     * @see "ISO 19142:2010, Table 3:  WFS exception codes"
     */
    @Test
    public void insertInvalidFeature() {
        try {
            this.reqEntity = docBuilder.parse(getClass().getResourceAsStream(
                    "InsertUnrecognizedFeature.xml"));
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to parse InsertUnrecognizedFeature.xml from classpath",
                    e);
        }
        ProtocolBinding binding = wfsClient.getAnyTransactionBinding();
        URI endpoint = ServiceMetadataUtils.getOperationEndpoint(
                this.wfsMetadata, WFS2.TRANSACTION, binding);
        ClientResponse rsp = wfsClient.submitRequest(new DOMSource(
                this.reqEntity), binding, endpoint);
        Assert.assertEquals(rsp.getStatus(),
                ClientResponse.Status.BAD_REQUEST.getStatusCode(),
                ErrorMessage.get(ErrorMessageKeys.UNEXPECTED_STATUS));
        this.rspEntity = rsp.getEntity(Document.class);
        String xpath = "//ows:Exception[@exceptionCode = 'InvalidValue']";
        ETSAssert.assertXPath(xpath, this.rspEntity.getDocumentElement(), null);
    }

    /**
     * Extracts a list of identifiers for features that were successfully
     * inserted. The identifiers are accessed using this XPath expression:
     * "//wfs:InsertResults/wfs:Feature/fes:ResourceId/@rid".
     * 
     * @param entity
     *            A Document representing a transaction response entity
     *            (wfs:TransactionResponse).
     * @return A List containing one or more feature identifiers (gml:id
     *         attribute values).
     */
    List<String> extractFeatureIdentifiers(Document entity) {
        List<String> resourceIDs = new ArrayList<String>();
        String xpath = "//wfs:InsertResults/wfs:Feature/fes:ResourceId/@rid";
        Map<String, String> nsBindings = new HashMap<String, String>();
        nsBindings.put(Namespaces.WFS, "wfs");
        nsBindings.put(Namespaces.FES, "fes");
        try {
            NodeList ridNodes = XMLUtils.evaluateXPath(entity, xpath,
                    nsBindings);
            for (int i = 0; i < ridNodes.getLength(); i++) {
                resourceIDs.add(ridNodes.item(i).getTextContent());
            }
        } catch (XPathExpressionException e) {
            throw new RuntimeException(e);
        }
        return resourceIDs;
    }

    /**
     * Creates a new representation of a feature instance. First, an attempt is
     * made to retrieve a feature representation from the SUT; if this fails or
     * no instances exist then one is generated using an application schema
     * (Note: this facility is not yet implemented). The gml:id attribute is
     * modified and a new gml:identifier is added.
     * 
     * @param featureType
     *            A QName representing the qualified name of some feature type.
     * @return A Node (Element) node representing a feature instance.
     */
    Node createFeatureInstance(QName featureType) {
        Document entity = wfsClient.getFeatureByType(featureType, 1, null);
        NodeList features = entity.getElementsByTagNameNS(
                featureType.getNamespaceURI(), featureType.getLocalPart());
        if (features.getLength() == 0) {
            // TODO: try generating minimal instance from schema
            throw new NullPointerException(
                    "Unable to obtain feature instance of type " + featureType);
        }
        Element feature = (Element) features.item(0);
        feature.setAttributeNS(Namespaces.GML, "gml:id",
                "id-" + System.currentTimeMillis());
        insertRandomIdentifier(feature);
        return feature.cloneNode(true);
    }

    /**
     * Inserts a user-assigned gml:identifier element having a random UUID
     * value.
     * 
     * @param feature
     *            An Element node representing a GML feature.
     */
    void insertRandomIdentifier(Element feature) {
        QName propName = new QName(Namespaces.GML, "identifier");
        Element identifier = XMLUtils.createElement(propName);
        identifier.setAttribute("codeSpace", "http://cite.opengeospatial.org/");
        identifier.setTextContent(UUID.randomUUID().toString());
        WFSRequest.insertGMLProperty(feature, identifier);
    }
}
