package net.eflan.projects.secretsnowman;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import com.twilio.Twilio;
import com.twilio.twiml.messaging.Body;
import com.twilio.twiml.messaging.Message;
import com.twilio.twiml.MessagingResponse;
import com.twilio.type.PhoneNumber;

import com.fasterxml.jackson.core.type.TypeReference;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.singletonMap;

public class LambdaHandler implements RequestHandler<Map<String, Object>, String>{
    // Hide Java8 ugliness in helper method
    static Map<String, String> mapOf(final String ... args) {
        final String[][] pairs = new String[args.length / 2][2];
        for(int i = 0; i < args.length; i += 2) {
            pairs[i / 2][0] = args[i];
            pairs[i / 2][1] = args[i + 1];
        }

        return Stream.of(pairs).collect(Collectors.collectingAndThen(
                Collectors.toMap(data -> data[0], data -> data[1]),
                Collections::unmodifiableMap));
    }

    private final DynamoDbClient ddb;
    private final SecretsManagerClient smc;

    public static final String INTRO_COMMAND = "intro";
    public static final String INTRO_FORMAT = "\u2603 Ahoy %s! Welcome to Secret Snowman!️\n\uD83C\uDF81You are buying a present for %s.\uD83C\uDF81\nPlease reply \"gifted\" to mark your gift as purchased.\uD83C\uDF81\nYou can reply \"menu\" for more options.\u2744";

    public static final String MENU_COMMAND = "menu";
    public static final String MENU_FORMAT = "\u26C4 I'm here to help!\n\u2744Text \"gifted\" to mark your gift as purchased.\n\u2744Text \"assignment\" to see whom you are assigned.\n\u2744Text \"reset\" if you accidentally marked your gift as purchased.\n\u2744Text \"intro\" to see the introductory message again.\u2603";

    public static final String GIFTED_COMMAND = "gifted";
    public static final String GIFTED_FORMAT = "\u2603 \uD83C\uDF81 Awesome! You're going to make this the greatest Secret Snowman ever for %s!\nJust text \"reset\" if you accidentally marked your gift as purchased.\u2603";

    public static final String ASSIGNMENT_COMMAND = "assignment";
    public static final String ASSIGNMENT_FORMAT = "\u2603 You are buying a present for %s.\uD83C\uDF81";

    public static final String RESET_COMMAND = "reset";
    public static final String RESET_FORMAT = "\u2603 Ok! You still need to buy a gift for %s.\uD83C\uDF81";

    public static final String UNKNOWN_COMMAND = "unknown";
    public static final String UNKNOWN_FORMAT = "\u2603 I'm sorry. I didn't understand \"%s\".\nPlease text \"menu\" for help.\u26C4";

    public static final String CHECK_NO_GIFT_COMMAND = "no gifts";
    public static final String CHECK_GIFTED_COMMAND = "gifts";
    public static final String ASSIGN_GIFTS_COMMAND = "assign gifts";
    public static final String REMIND_COMMAND = "remind";
    public static final String REMINDER_FORMAT = "\u2603 Secret Snowman here!\u2744 %s, you still need to buy a gift for %s.\uD83C\uDF81";

    private final Map<String, String> twimlMap =  mapOf(
            INTRO_COMMAND, INTRO_FORMAT,
            MENU_COMMAND, MENU_FORMAT,
            GIFTED_COMMAND, GIFTED_FORMAT,
            ASSIGNMENT_COMMAND, ASSIGNMENT_FORMAT,
            RESET_COMMAND, RESET_FORMAT,
            UNKNOWN_COMMAND, UNKNOWN_FORMAT);

    final static String BODY = "Body";
    final static String FROM = "From";

    public static String toTWIML(final String text) {
        final Body body = new Body.Builder(text).build();
        final Message message = new Message.Builder().body(body).build();
        final MessagingResponse response = new MessagingResponse.Builder().message(message).build();
        return response.toXml();
    }

    private String secretSnowmanTable = null;
    private PhoneNumber adminPhoneNumber = null;
    private PhoneNumber secretSnowmanPhoneNumber = null;

    private String twilioAccountSID = null;
    private String twilioAccountSecret = null;
    private SendSMS sendSMS = (ph, s, p, a) -> "SID";
    private boolean isTwilioInitialized = false;

    public LambdaHandler() {
        this.ddb = DynamoDbClient.builder()
                .region(Region.US_WEST_2)
                .build();

        this.smc = SecretsManagerClient.builder()
                .region(Region.US_WEST_2)
                .build();

        this.sendSMS = LambdaHandler::sendSMSviaTwilio;
    }

    public LambdaHandler(final DynamoDbClient dynamoDbClient, final SecretsManagerClient secretsManagerClient) {
        this.ddb = dynamoDbClient;
        this.smc = secretsManagerClient;
        // Use default implementation of sendSMS
        // Don't attempt to initialize Twilio SDK
        this.isTwilioInitialized = true;
    }

    public void getSecrets() throws java.io.IOException {

        final GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                .secretId("SecretSnowmanSecrets")
                .build();

        final GetSecretValueResponse secrets = this.smc.getSecretValue(getSecretValueRequest);

        // Parse the JSON blob into a map of (String -> String)
        final ObjectMapper mapper = new ObjectMapper();
        final Map<String, String> secretKeysAndValues = mapper.readValue(
                secrets.secretString(),
                new TypeReference<Map<String, String>>(){});

        this.secretSnowmanTable = secretKeysAndValues.get("StateDynamoTable");
        this.adminPhoneNumber = new PhoneNumber(secretKeysAndValues.get("AdminPhoneNumber"));
        this.secretSnowmanPhoneNumber = new PhoneNumber(secretKeysAndValues.get("SecretSnowmanPhoneNumber"));
        this.twilioAccountSID = secretKeysAndValues.get("TwilioAccountSID");
        this.twilioAccountSecret = secretKeysAndValues.get("TwilioAccountSecret");
    }

    private static String createResponse(
            final DynamoDbClient ddb,
            final String secretSnowmanTable,
            final Map<String, String> twimlMap,
            final SecretSnowmanState state,
            final String key) {

        final SecretSnowmanState recipient = dynamoLookup(
                ddb,
                secretSnowmanTable,
                state.assigned().toString());

        String text = null;
        if(INTRO_COMMAND.equals(key)) {
            text = String.format(twimlMap.get(key), state.name(), recipient.name());
        }
        else if(MENU_COMMAND.equals(key)) {
            text = twimlMap.get(key);
        }
        else if(UNKNOWN_COMMAND.equals(key)) {
            text = String.format(twimlMap.get(key), key);
        }
        else {
            text = String.format(twimlMap.get(key), recipient.name());
        }

        return toTWIML(text);
    }

    private static SecretSnowmanState dynamoLookup(
            final DynamoDbClient dbc,
            final String secretSnowmanTable,
            final String phoneNumber) {

        final Map<String, AttributeValue> key =
                singletonMap("phone-number", AttributeValue.builder().s(phoneNumber).build());

        final GetItemRequest get = GetItemRequest.builder()
                .consistentRead(true)
                .tableName(secretSnowmanTable)
                .key(key)
                .build();

        final GetItemResponse response = dbc.getItem(get);
        return SecretSnowmanState.from(response.item());
    }

    private static List<SecretSnowmanState> dynamoScanGifts(
            final DynamoDbClient dbc,
            final String secretSnowmanTable,
            final boolean giftGiven) {

        final Map<String, AttributeValue> value =
                singletonMap(":tf", AttributeValue.builder().bool(giftGiven).build());

        final ScanRequest scan = ScanRequest.builder()
                .consistentRead(true)
                .tableName(secretSnowmanTable)
                .filterExpression("#giftPurchased = :tf")
                .expressionAttributeValues(value)
                .expressionAttributeNames(singletonMap("#giftPurchased", "gift-purchased"))
                .build();

        final ScanResponse response = dbc.scan(scan);
        final Stream<Map<String, AttributeValue>> s = response.items().stream();
        return s.map(SecretSnowmanState::from).collect(Collectors.toList());
    }

    private static List<SecretSnowmanState> dynamoScanAll(
            final DynamoDbClient dbc,
            final String secretSnowmanTable) {
        final ScanRequest scan = ScanRequest.builder()
                .consistentRead(true)
                .tableName(secretSnowmanTable)
                .build();

        final ScanResponse response = dbc.scan(scan);
        final Stream<Map<String, AttributeValue>> s = response.items().stream();
        return s.map(SecretSnowmanState::from).collect(Collectors.toList());
    }

    private String assignGifts(
            final PhoneNumber secretSnowmanPhoneNumber,
            final DynamoDbClient dynamoDbClient,
            final String secretSnowmanTable,
            final List<SecretSnowmanState> people) {

        // Shuffle the list of people until every person is aligned with someone they're allowed to be assigned
        final List<SecretSnowmanState> assignments = new ArrayList<>(people);
        do {
            Collections.shuffle(assignments);
        } while(!constraintsSatisfied(people, assignments));

        final Iterator<SecretSnowmanState> peopleIterator = people.iterator();
        final Iterator<SecretSnowmanState> assignmentsIterator = assignments.iterator();
        final List<String> messageSIDs = new ArrayList<>();

        // Update assignment in the DynamoDB table and send an intro SMS to the person
        // letting them know their assignment.
        while (peopleIterator.hasNext() && assignmentsIterator.hasNext()) {
            final SecretSnowmanState person = peopleIterator.next();
            final SecretSnowmanState assignment = assignmentsIterator.next();

            dynamoUpdateAssigned(
                    dynamoDbClient,
                    secretSnowmanTable,
                    person.phone().toString(),
                    assignment.phone().toString());

            messageSIDs.add(
                    person.name() + ": " +
                            sendIntroSMS(this.sendSMS, secretSnowmanPhoneNumber, person, assignment) + '\n');
        }

        return toTWIML(messageSIDs.stream().collect(Collectors.joining()));
    }

    private static boolean constraintsSatisfied(
            final List<SecretSnowmanState> people,
            final List<SecretSnowmanState> assignments) {

        final Iterator<SecretSnowmanState> peopleIterator = people.iterator();
        final Iterator<SecretSnowmanState> assignmentsIterator = assignments.iterator();

        while (peopleIterator.hasNext() && assignmentsIterator.hasNext()) {
            final SecretSnowmanState person = peopleIterator.next();
            final SecretSnowmanState assignment = assignmentsIterator.next();

            if(person.phone().equals(assignment.phone()) || person.cannot().contains(assignment.phone())) {
                return false;
            }
        }

        return true;
    }

    private static void dynamoUpdateAssigned(
            final DynamoDbClient dbc,
            final String secretSnowmanTable,
            final String phoneNumber,
            final String assignmentPhoneNumber) {

        final Map<String, AttributeValue> key =
                singletonMap("phone-number", AttributeValue.builder().s(phoneNumber).build());

        final Map<String, AttributeValue> assignment =
                singletonMap(":pn", AttributeValue.builder().s(assignmentPhoneNumber).build());

        final UpdateItemRequest update = UpdateItemRequest.builder()
                .tableName(secretSnowmanTable)
                .key(key)
                .updateExpression("set assigned = :pn")
                .expressionAttributeValues(assignment)
                .build();

        dbc.updateItem(update);
    }

    private interface SendSMS {
        String send(
                final PhoneNumber secretSnowmanPhoneNumber,
                final String format,
                final SecretSnowmanState person,
                final SecretSnowmanState assignment);
    }

    private static String sendSMSviaTwilio(
        final PhoneNumber secretSnowmanPhoneNumber,
        final String format,
        final SecretSnowmanState person,
        final SecretSnowmanState assignment) {

        final com.twilio.rest.api.v2010.account.Message message =
                com.twilio.rest.api.v2010.account.Message.creator(
                        person.phone(),
                        secretSnowmanPhoneNumber,
                        String.format(format, person.name(), assignment.name())).create();

        return message.getSid();
    }

    private static String sendIntroSMS(
            final SendSMS sendSmsFunction,
            final PhoneNumber secretSnowmanPhoneNumber,
            final SecretSnowmanState person,
            final SecretSnowmanState assignment) {

        return sendSmsFunction.send(secretSnowmanPhoneNumber, INTRO_FORMAT, person, assignment);
    }


    private static String sendReminderSMS(
            final SendSMS sendSmsFunction,
            final PhoneNumber secretSnowmanPhoneNumber,
            final SecretSnowmanState person,
            final SecretSnowmanState assignment) {

        return sendSmsFunction.send(secretSnowmanPhoneNumber, REMINDER_FORMAT, person, assignment);
    }

    private static String remindNoGifts(
            final SendSMS sendSmsFunction,
            final PhoneNumber secretSnowmanPhoneNumber,
            final List<SecretSnowmanState> people) {

        StringBuilder status = new StringBuilder("");
        for(final SecretSnowmanState person : people) {
            if(!person.gifted()) {
                final Optional<SecretSnowmanState> assignedO =
                        people.stream().filter(p -> p.phone().equals(person.assigned())).findAny();

                if(assignedO.isPresent()) {
                    sendReminderSMS(sendSmsFunction, secretSnowmanPhoneNumber, person, assignedO.get());
                    status.append(person.name());
                    status.append(": success\n");
                }
                else {
                    status.append(person.name());
                    status.append(": failure\n");
                }
            }
        }

        return toTWIML(status.toString());
    }

    private static void dynamoUpdateGifted(
            final DynamoDbClient dbc,
            final String secretSnowmanTable,
            final String phoneNumber,
            final boolean giftGiven) {

        final Map<String, AttributeValue> key =
                singletonMap("phone-number", AttributeValue.builder().s(phoneNumber).build());
        final Map<String, AttributeValue> gifted =
                singletonMap(":gifted", AttributeValue.builder().bool(giftGiven).build());

        final UpdateItemRequest update = UpdateItemRequest.builder()
                .tableName(secretSnowmanTable)
                .key(key)
                .updateExpression("set #giftPurchased = :gifted")
                .expressionAttributeValues(gifted)
                .expressionAttributeNames(singletonMap("#giftPurchased", "gift-purchased"))
                .build();

        dbc.updateItem(update);
    }

    private static String extractNames(final String prefix, final List<SecretSnowmanState> people) {
        return prefix + people.stream().map(p -> p.name() + " (" + p.phone() + ")\n").collect(Collectors.joining());
    }

    public String handleRequest(final Map<String, Object> req, final Context context) {
        try {
            this.getSecrets();

            // Unit tests set this to true to avoid using the real Twilio SDK
            if(!this.isTwilioInitialized) {
                Twilio.init(this.twilioAccountSID, this.twilioAccountSecret);
            }

            String key = UNKNOWN_COMMAND;

            if (req.containsKey(BODY)) {
                key = URLDecoder.decode(req.get(BODY).toString(), "UTF-8").toLowerCase().trim();
            }

            if (req.containsKey(FROM)) {
                final PhoneNumber from = new PhoneNumber(URLDecoder.decode(req.get(FROM).toString(), "UTF-8"));

                if (key.equals(CHECK_NO_GIFT_COMMAND) && from.equals(this.adminPhoneNumber)) {

                    return toTWIML(
                            extractNames(
                                    "No Gift:\n",
                                    dynamoScanGifts(this.ddb, this.secretSnowmanTable,false)));

                } else if (key.equals(CHECK_GIFTED_COMMAND) && from.equals(this.adminPhoneNumber)) {

                    return toTWIML(
                            extractNames(
                                    "Gift:\n",
                                    dynamoScanGifts(this.ddb, secretSnowmanTable,true)));

                } else if (key.equals(ASSIGN_GIFTS_COMMAND) && from.equals(this.adminPhoneNumber)) {

                    return assignGifts(
                            this.secretSnowmanPhoneNumber,
                            this.ddb,
                            this.secretSnowmanTable,
                            dynamoScanAll(this.ddb, this.secretSnowmanTable));

                } else if (key.equals(REMIND_COMMAND) && from.equals(adminPhoneNumber)) {

                    return remindNoGifts(
                            this.sendSMS,
                            this.secretSnowmanPhoneNumber,
                            dynamoScanAll(this.ddb, this.secretSnowmanTable));

                } else {
                    final SecretSnowmanState state = dynamoLookup(this.ddb, this.secretSnowmanTable, from.toString());

                    if (!twimlMap.containsKey(key)) {
                        return toTWIML(String.format(UNKNOWN_FORMAT, key) + from + ", " + this.adminPhoneNumber);
                    } else {
                        if (key.equals(GIFTED_COMMAND)) {
                            dynamoUpdateGifted(this.ddb, this.secretSnowmanTable, from.toString(), true);
                        } else if (key.equals(RESET_COMMAND)) {
                            dynamoUpdateGifted(this.ddb, this.secretSnowmanTable, from.toString(), false);
                        }

                        return createResponse(this.ddb, this.secretSnowmanTable, this.twimlMap, state, key);
                    }
                }
            } else {
                return toTWIML("\u2744\u2744\u2744Sorry, I don't recognize your phone number. Are you sure you're participating in Secret Snowman?\u2603");
            }
        } catch (final UnsupportedEncodingException e) {
            return toTWIML("\u2744Internal Server Error - 0\u2744");
        } catch(final IOException e) {
            return toTWIML("\u2744Internal Server Error - 1\u2744");
        }
    }
}
