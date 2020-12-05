package net.eflan.projects.secretsnowman;

import com.twilio.type.PhoneNumber;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class SecretSnowmanState {
    public static final String GIFTS_TABLE = "secret-snowman-state";
    public static final String ASSIGNED_KEY = "assigned";
    public static final String CANNOT_MATCH_KEY = "cannot-match";
    public static final String GIFT_GIVER_NAME_KEY = "gift-giver-name";
    public static final String GIFT_PURCHASE_KEY = "gift-purchased";
    public static final String ADDRESS_KEY = "address";
    public static final String PHONE_NUMBER_KEY = "phone-number";
    public static final String PRIMARY_KEY = PHONE_NUMBER_KEY;

    private final PhoneNumber assigned;
    private final List<PhoneNumber> cannotMatch;
    private final String address;
    private final String giftGiverName;
    private final boolean giftPurchased;
    private final PhoneNumber phoneNumber;

    private SecretSnowmanState(
            final PhoneNumber assigned,
            final String address,
            final List<PhoneNumber> cannotMatch,
            final String giftGiverName,
            final boolean giftPurchased,
            final PhoneNumber phoneNumber) {

        this.assigned = assigned;
        this.cannotMatch = cannotMatch;
        this.address = address;
        this.giftGiverName = giftGiverName;
        this.giftPurchased = giftPurchased;
        this.phoneNumber = phoneNumber;
    }

    public static List<PhoneNumber> toPhoneNumberList(final List<AttributeValue> numbers) {
        final List<PhoneNumber> phoneNumbers = new ArrayList<>(numbers.size());
        for(final AttributeValue v : numbers) {
            phoneNumbers.add(new PhoneNumber(v.s()));
        }

        return phoneNumbers;
    }

    public static SecretSnowmanState from(final Map<String, AttributeValue> item) {
        String assigned = "+12065550000";
        if(item.containsKey("assigned")) {
            assigned = item.get("assigned").s();
        }

        List<AttributeValue> cannotMatch = Collections.emptyList();;
        if(item.containsKey("cannot-match")) {
            cannotMatch = item.get("cannot-match").l();
        }

        String name = "n/a";
        if(item.containsKey("gift-giver-name")) {
            name = item.get("gift-giver-name").s();
        }

        boolean giftPurchased = false;
        if(item.containsKey("gift-purchased")) {
            giftPurchased = item.get("gift-purchased").bool();
        }

        String phone = "+12065550000";
        if(item.containsKey("phone-number")) {
            phone = item.get("phone-number").s();
        }

        String address = "n/a";
        if(item.containsKey("address")) {
            address = item.get("address").s();
        }

        return new SecretSnowmanState(
                new PhoneNumber(assigned),
                address,
                toPhoneNumberList(cannotMatch),
                name,
                giftPurchased,
                new PhoneNumber(phone));
    }

    public PhoneNumber assigned() { return this.assigned; }
    public List<PhoneNumber> cannot() { return this.cannotMatch; }
    public String name() { return this.giftGiverName; }
    public boolean purchased() { return this.giftPurchased; }
    public PhoneNumber phone() { return this.phoneNumber; }
    public boolean gifted() { return this.giftPurchased; }
    public String address() { return this.address; }

    public String toString() {

        return String.format(
                "{ %s, %s, %s, assigned: %s, cannot-match: %s, gift-purchased: %s }",
                name(),
                phone(),
                address(),
                assigned(),
                cannot().toString(),
                Boolean.toString(gifted()));
    }
}
