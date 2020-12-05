package net.eflan.projects.secretsnowman;

import com.amazonaws.services.lambda.runtime.Context;

import org.junit.*;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LambdaTests {
    final DynamoDbClient mockDynamoClient = mock(DynamoDbClient.class);
    final SecretsManagerClient mockSecretsManagerClient = mock(SecretsManagerClient.class);
    final LambdaHandler handler = new LambdaHandler(mockDynamoClient, mockSecretsManagerClient);

    public static Map<String, AttributeValue> makeItem(
            final String name,
            final String phone,
            final String address,
            final List<String> cannot,
            final String assigned,
            final boolean giftPurchased) {

        final List<AttributeValue> cannotAttributes = cannot.stream().map(
                s -> AttributeValue.builder().s(s).build()).collect(Collectors.toList());
        final AttributeValue cannotMatch =
                AttributeValue.builder().l(cannotAttributes).build();

        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(SecretSnowmanState.PHONE_NUMBER_KEY, AttributeValue.builder().s(phone).build());
        item.put(SecretSnowmanState.ADDRESS_KEY, AttributeValue.builder().s(address).build());
        item.put(SecretSnowmanState.GIFT_GIVER_NAME_KEY, AttributeValue.builder().s(name).build());
        item.put(SecretSnowmanState.CANNOT_MATCH_KEY, cannotMatch);
        item.put(SecretSnowmanState.ASSIGNED_KEY, AttributeValue.builder().s(assigned).build());
        item.put(SecretSnowmanState.GIFT_PURCHASE_KEY, AttributeValue.builder().bool(giftPurchased).build());

        return item;
    }

    private static final Answer<GetSecretValueResponse> getSecretValueResponse() {
        return new Answer<GetSecretValueResponse>() {
            public GetSecretValueResponse answer(final InvocationOnMock invocation) {
                return GetSecretValueResponse.builder()
                        .secretString("{ \"TwilioAccountSID\":\"foo\", " +
                                "\"TwilioAccountSecret\":\"bar\", " +
                                "\"SecretSnowmanTable\":\"test-table\", " +
                                "\"AdminPhoneNumber\":\"+15555550000\", " +
                                "\"SecretSnowmanPhoneNumber\":\"+15555550001\"}")
                        .build();
            }
        };
    }

    public Map<String, Object> setupTest(final String command) {
        return setupTest(command, "%2B15555550000");
    }

    public Map<String, Object> setupTest (final String command, final String from) {
        final Map<String, Object> request = new HashMap<String, Object>();
        request.put("From", from);
        request.put("Body", command);

        final Map<String, AttributeValue> item = makeItem(
                "unit test name",
                "+15555550002",
                "address",
                Arrays.asList("+15555550003"),
                "+15555550004",
                false);

        when(mockDynamoClient.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());

        when(mockSecretsManagerClient.getSecretValue(any(GetSecretValueRequest.class))).thenAnswer(getSecretValueResponse());

        return request;
    }

    @Test
    public void testIntro() {
        Assert.assertEquals(
                "\"intro\" command is handled",
                LambdaHandler.toTWIML(String.format(LambdaHandler.INTRO_FORMAT, "unit test name", "unit test name", "address")),
                handler.handleRequest(setupTest("intro"), mock(Context.class)));
    }

    @Test
    public void testMenu() {
        Assert.assertEquals(
                "\"menu\" command is handled",
                LambdaHandler.toTWIML(LambdaHandler.MENU_FORMAT),
                handler.handleRequest(setupTest("menu"), mock(Context.class)));
    }

    @Test
    public void testAssignment() {
        Assert.assertEquals(
                "\"assignment\" command is handled",
                LambdaHandler.toTWIML(String.format(LambdaHandler.ASSIGNMENT_FORMAT, "unit test name")),
                handler.handleRequest(setupTest("assignment"), mock(Context.class)));
    }

    @Test
    public void testGifted() {
        Assert.assertEquals(
                "\"gifted\" command is handled",
                LambdaHandler.toTWIML(String.format(LambdaHandler.GIFTED_FORMAT, "unit test name")),
                handler.handleRequest(setupTest("gifted"), mock(Context.class)));
    }

    @Test
    public void testReset() {
        Assert.assertEquals(
                "\"reset\" command is handled",
                LambdaHandler.toTWIML(String.format(LambdaHandler.RESET_FORMAT, "unit test name")),
                handler.handleRequest(setupTest("reset"), mock(Context.class)));
    }

    @Test
    public void testNoGifts() {
        final Map<String, AttributeValue> item1 = makeItem(
                "unit test name 1",
                "+15555550002",
                "address",
                Arrays.asList("+15555550003"),
                "+15555550004",
                false);

        final Map<String, AttributeValue> item2 = makeItem(
                "unit test name 2",
                "+15555550003",
                "address",
                Arrays.asList("+15555550004", "+15555550005"),
                "+15555550002",
                false);

        final Map<String, AttributeValue> item3 = makeItem(
                "unit test name 3",
                "+15555550004",
                "address",
                Arrays.asList("+15555550002"),
                "+15555550003",
                false);

        when(mockDynamoClient.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder().items(item1, item2, item3).build());

        final String response = handler.handleRequest(setupTest("no gifts"), mock(Context.class));

        Assert.assertTrue("\"no gifts\" command is handled", response.contains("unit test name 1"));
        Assert.assertTrue("\"no gifts\" command is handled", response.contains("unit test name 2"));
        Assert.assertTrue("\"no gifts\" command is handled", response.contains("unit test name 3"));
    }

    @Test
    public void testGifts() {
        final Map<String, AttributeValue> item1 = makeItem(
                "unit test name 1",
                "+15555550002",
                "address",
                Arrays.asList("+15555550003"),
                "+15555550004",
                true);

        final Map<String, AttributeValue> item2 = makeItem(
                "unit test name 2",
                "+15555550003",
                "address",
                Arrays.asList("+15555550004", "+15555550005"),
                "+15555550002",
                false);

        final Map<String, AttributeValue> item3 = makeItem(
                "unit test name 3",
                "+15555550004",
                "address",
                Arrays.asList("+15555550002"),
                "+15555550003",
                true);

        when(mockDynamoClient.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder().items(item1, item3).build());

        final String response = handler.handleRequest(setupTest("gifts"), mock(Context.class));

        Assert.assertTrue("\"gifts\" command is handled", response.contains("unit test name 1"));
        Assert.assertTrue("\"gifts\" command is handled", !response.contains("unit test name 2"));
        Assert.assertTrue("\"gifts\" command is handled", response.contains("unit test name 3"));
    }
}
