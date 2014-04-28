package org.whispersystems.test;

import android.test.AndroidTestCase;

import org.whispersystems.libaxolotl.DuplicateMessageException;
import org.whispersystems.libaxolotl.IdentityKey;
import org.whispersystems.libaxolotl.InvalidKeyException;
import org.whispersystems.libaxolotl.InvalidKeyIdException;
import org.whispersystems.libaxolotl.InvalidMessageException;
import org.whispersystems.libaxolotl.InvalidVersionException;
import org.whispersystems.libaxolotl.LegacyMessageException;
import org.whispersystems.libaxolotl.SessionBuilder;
import org.whispersystems.libaxolotl.SessionCipher;
import org.whispersystems.libaxolotl.StaleKeyExchangeException;
import org.whispersystems.libaxolotl.UntrustedIdentityException;
import org.whispersystems.libaxolotl.ecc.Curve;
import org.whispersystems.libaxolotl.ecc.ECKeyPair;
import org.whispersystems.libaxolotl.ecc.ECPublicKey;
import org.whispersystems.libaxolotl.protocol.CiphertextMessage;
import org.whispersystems.libaxolotl.protocol.KeyExchangeMessage;
import org.whispersystems.libaxolotl.protocol.PreKeyWhisperMessage;
import org.whispersystems.libaxolotl.state.IdentityKeyStore;
import org.whispersystems.libaxolotl.state.PreKey;
import org.whispersystems.libaxolotl.state.PreKeyRecord;
import org.whispersystems.libaxolotl.state.PreKeyStore;
import org.whispersystems.libaxolotl.state.SessionStore;
import org.whispersystems.libaxolotl.util.Pair;

import java.util.HashSet;
import java.util.Set;

public class SessionBuilderTest extends AndroidTestCase {

  private static final long ALICE_RECIPIENT_ID = 5L;
  private static final long BOB_RECIPIENT_ID   = 2L;

  public void testBasicPreKey()
      throws InvalidKeyException, InvalidVersionException, InvalidMessageException, InvalidKeyIdException, DuplicateMessageException, LegacyMessageException, UntrustedIdentityException {
    SessionStore     aliceSessionStore     = new InMemorySessionStore();
    PreKeyStore      alicePreKeyStore      = new InMemoryPreKeyStore();
    IdentityKeyStore aliceIdentityKeyStore = new InMemoryIdentityKeyStore();
    SessionBuilder   aliceSessionBuilder   = new SessionBuilder(aliceSessionStore, alicePreKeyStore,
                                                                aliceIdentityKeyStore,
                                                                BOB_RECIPIENT_ID, 1);

    SessionStore     bobSessionStore     = new InMemorySessionStore();
    PreKeyStore      bobPreKeyStore      = new InMemoryPreKeyStore();
    IdentityKeyStore bobIdentityKeyStore = new InMemoryIdentityKeyStore();
    SessionBuilder   bobSessionBuilder   = new SessionBuilder(bobSessionStore, bobPreKeyStore,
                                                              bobIdentityKeyStore,
                                                              ALICE_RECIPIENT_ID, 1);

    InMemoryPreKey bobPreKey = new InMemoryPreKey(31337, Curve.generateKeyPair(true),
                                                  bobIdentityKeyStore.getIdentityKeyPair().getPublicKey(),
                                                  bobIdentityKeyStore.getLocalRegistrationId());

    aliceSessionBuilder.process(bobPreKey);

    assertTrue(aliceSessionStore.contains(BOB_RECIPIENT_ID, 1));
    assertTrue(!aliceSessionStore.load(BOB_RECIPIENT_ID, 1).getSessionState().getNeedsRefresh());

    String            originalMessage    = "L'homme est condamné à être libre";
    SessionCipher     aliceSessionCipher = new SessionCipher(aliceSessionStore, BOB_RECIPIENT_ID, 1);
    CiphertextMessage outgoingMessage    = aliceSessionCipher.encrypt(originalMessage.getBytes());

    assertTrue(outgoingMessage.getType() == CiphertextMessage.PREKEY_TYPE);

    PreKeyWhisperMessage incomingMessage = new PreKeyWhisperMessage(outgoingMessage.serialize());
    bobPreKeyStore.store(31337, bobPreKey);
    bobSessionBuilder.process(incomingMessage);

    assertTrue(bobSessionStore.contains(ALICE_RECIPIENT_ID, 1));

    SessionCipher bobSessionCipher = new SessionCipher(bobSessionStore, ALICE_RECIPIENT_ID, 1);
    byte[]        plaintext        = bobSessionCipher.decrypt(incomingMessage.getWhisperMessage().serialize());

    assertTrue(originalMessage.equals(new String(plaintext)));

    CiphertextMessage bobOutgoingMessage = bobSessionCipher.encrypt(originalMessage.getBytes());
    assertTrue(bobOutgoingMessage.getType() == CiphertextMessage.WHISPER_TYPE);

    byte[] alicePlaintext = aliceSessionCipher.decrypt(bobOutgoingMessage.serialize());
    assertTrue(new String(alicePlaintext).equals(originalMessage));

    runInteraction(aliceSessionStore, bobSessionStore);

    aliceSessionStore     = new InMemorySessionStore();
    aliceIdentityKeyStore = new InMemoryIdentityKeyStore();
    aliceSessionBuilder   = new SessionBuilder(aliceSessionStore, alicePreKeyStore,
                                               aliceIdentityKeyStore,
                                               BOB_RECIPIENT_ID, 1);
    aliceSessionCipher = new SessionCipher(aliceSessionStore, BOB_RECIPIENT_ID, 1);

    bobPreKey = new InMemoryPreKey(31338, Curve.generateKeyPair(true),
                                   bobIdentityKeyStore.getIdentityKeyPair().getPublicKey(),
                                   bobIdentityKeyStore.getLocalRegistrationId());

    bobPreKeyStore.store(31338, bobPreKey);
    aliceSessionBuilder.process(bobPreKey);

    outgoingMessage = aliceSessionCipher.encrypt(originalMessage.getBytes());

    try {
      bobSessionBuilder.process(new PreKeyWhisperMessage(outgoingMessage.serialize()));
      throw new AssertionError("shouldn't be trusted!");
    } catch (UntrustedIdentityException uie) {
      bobIdentityKeyStore.saveIdentity(ALICE_RECIPIENT_ID, new PreKeyWhisperMessage(outgoingMessage.serialize()).getIdentityKey());
      bobSessionBuilder.process(new PreKeyWhisperMessage(outgoingMessage.serialize()));
    }

    plaintext = bobSessionCipher.decrypt(new PreKeyWhisperMessage(outgoingMessage.serialize()).getWhisperMessage().serialize());
    assertTrue(new String(plaintext).equals(originalMessage));

    bobPreKey = new InMemoryPreKey(31337, Curve.generateKeyPair(true),
                                   aliceIdentityKeyStore.getIdentityKeyPair().getPublicKey(),
                                   bobIdentityKeyStore.getLocalRegistrationId());

    try {
      aliceSessionBuilder.process(bobPreKey);
      throw new AssertionError("shoulnd't be trusted!");
    } catch (UntrustedIdentityException uie) {
      // good
    }
  }

  public void testBasicKeyExchange() throws InvalidKeyException, LegacyMessageException, InvalidMessageException, DuplicateMessageException, UntrustedIdentityException, StaleKeyExchangeException {
    SessionStore     aliceSessionStore     = new InMemorySessionStore();
    PreKeyStore      alicePreKeyStore      = new InMemoryPreKeyStore();
    IdentityKeyStore aliceIdentityKeyStore = new InMemoryIdentityKeyStore();
    SessionBuilder   aliceSessionBuilder   = new SessionBuilder(aliceSessionStore, alicePreKeyStore,
                                                                aliceIdentityKeyStore,
                                                                BOB_RECIPIENT_ID, 1);

    SessionStore     bobSessionStore     = new InMemorySessionStore();
    PreKeyStore      bobPreKeyStore      = new InMemoryPreKeyStore();
    IdentityKeyStore bobIdentityKeyStore = new InMemoryIdentityKeyStore();
    SessionBuilder   bobSessionBuilder   = new SessionBuilder(bobSessionStore, bobPreKeyStore,
                                                              bobIdentityKeyStore,
                                                              ALICE_RECIPIENT_ID, 1);

    KeyExchangeMessage aliceKeyExchangeMessage = aliceSessionBuilder.process();
    KeyExchangeMessage bobKeyExchangeMessage   = bobSessionBuilder.process(aliceKeyExchangeMessage);

    assertTrue(bobKeyExchangeMessage != null);
    assertTrue(aliceKeyExchangeMessage != null);

    KeyExchangeMessage response = aliceSessionBuilder.process(bobKeyExchangeMessage);

    assertTrue(response == null);
    assertTrue(aliceSessionStore.contains(BOB_RECIPIENT_ID, 1));
    assertTrue(bobSessionStore.contains(ALICE_RECIPIENT_ID, 1));

    runInteraction(aliceSessionStore, bobSessionStore);

    aliceSessionStore       = new InMemorySessionStore();
    aliceIdentityKeyStore   = new InMemoryIdentityKeyStore();
    aliceSessionBuilder     = new SessionBuilder(aliceSessionStore, alicePreKeyStore,
                                                 aliceIdentityKeyStore, BOB_RECIPIENT_ID, 1);
    aliceKeyExchangeMessage = aliceSessionBuilder.process();

    try {
      bobKeyExchangeMessage = bobSessionBuilder.process(aliceKeyExchangeMessage);
      throw new AssertionError("This identity shouldn't be trusted!");
    } catch (UntrustedIdentityException uie) {
      bobIdentityKeyStore.saveIdentity(ALICE_RECIPIENT_ID, aliceKeyExchangeMessage.getIdentityKey());
      bobKeyExchangeMessage = bobSessionBuilder.process(aliceKeyExchangeMessage);
    }

    assertTrue(aliceSessionBuilder.process(bobKeyExchangeMessage) == null);

    runInteraction(aliceSessionStore, bobSessionStore);
  }

  public void testSimultaneousKeyExchange()
      throws InvalidKeyException, DuplicateMessageException, LegacyMessageException, InvalidMessageException, UntrustedIdentityException, StaleKeyExchangeException {
    SessionStore     aliceSessionStore     = new InMemorySessionStore();
    PreKeyStore      alicePreKeyStore      = new InMemoryPreKeyStore();
    IdentityKeyStore aliceIdentityKeyStore = new InMemoryIdentityKeyStore();
    SessionBuilder   aliceSessionBuilder   = new SessionBuilder(aliceSessionStore, alicePreKeyStore,
                                                                aliceIdentityKeyStore,
                                                                BOB_RECIPIENT_ID, 1);

    SessionStore     bobSessionStore     = new InMemorySessionStore();
    PreKeyStore      bobPreKeyStore      = new InMemoryPreKeyStore();
    IdentityKeyStore bobIdentityKeyStore = new InMemoryIdentityKeyStore();
    SessionBuilder   bobSessionBuilder   = new SessionBuilder(bobSessionStore, bobPreKeyStore,
                                                              bobIdentityKeyStore,
                                                              ALICE_RECIPIENT_ID, 1);

    KeyExchangeMessage aliceKeyExchange = aliceSessionBuilder.process();
    KeyExchangeMessage bobKeyExchange   = bobSessionBuilder.process();

    assertTrue(aliceKeyExchange != null);
    assertTrue(bobKeyExchange != null);

    KeyExchangeMessage aliceResponse = aliceSessionBuilder.process(bobKeyExchange);
    KeyExchangeMessage bobResponse   = bobSessionBuilder.process(aliceKeyExchange);

    assertTrue(aliceResponse != null);
    assertTrue(bobResponse != null);

    KeyExchangeMessage aliceAck = aliceSessionBuilder.process(bobResponse);
    KeyExchangeMessage bobAck   = bobSessionBuilder.process(aliceResponse);

    assertTrue(aliceAck == null);
    assertTrue(bobAck == null);

    runInteraction(aliceSessionStore, bobSessionStore);
  }

  private void runInteraction(SessionStore aliceSessionStore, SessionStore bobSessionStore)
      throws DuplicateMessageException, LegacyMessageException, InvalidMessageException
  {
    SessionCipher aliceSessionCipher = new SessionCipher(aliceSessionStore, BOB_RECIPIENT_ID, 1);
    SessionCipher bobSessionCipher   = new SessionCipher(bobSessionStore, ALICE_RECIPIENT_ID, 1);

    String originalMessage = "smert ze smert";
    CiphertextMessage aliceMessage = aliceSessionCipher.encrypt(originalMessage.getBytes());

    assertTrue(aliceMessage.getType() == CiphertextMessage.WHISPER_TYPE);

    byte[] plaintext = bobSessionCipher.decrypt(aliceMessage.serialize());
    assertTrue(new String(plaintext).equals(originalMessage));

    CiphertextMessage bobMessage = bobSessionCipher.encrypt(originalMessage.getBytes());

    assertTrue(bobMessage.getType() == CiphertextMessage.WHISPER_TYPE);

    plaintext = aliceSessionCipher.decrypt(bobMessage.serialize());
    assertTrue(new String(plaintext).equals(originalMessage));

    for (int i=0;i<10;i++) {
      String loopingMessage = ("What do we mean by saying that existence precedes essence? " +
                               "We mean that man first of all exists, encounters himself, " +
                               "surges up in the world--and defines himself aftward. " + i);
      CiphertextMessage aliceLoopingMessage = aliceSessionCipher.encrypt(loopingMessage.getBytes());

      byte[] loopingPlaintext = bobSessionCipher.decrypt(aliceLoopingMessage.serialize());
      assertTrue(new String(loopingPlaintext).equals(loopingMessage));
    }

    for (int i=0;i<10;i++) {
      String loopingMessage = ("What do we mean by saying that existence precedes essence? " +
                               "We mean that man first of all exists, encounters himself, " +
                               "surges up in the world--and defines himself aftward. " + i);
      CiphertextMessage bobLoopingMessage = bobSessionCipher.encrypt(loopingMessage.getBytes());

      byte[] loopingPlaintext = aliceSessionCipher.decrypt(bobLoopingMessage.serialize());
      assertTrue(new String(loopingPlaintext).equals(loopingMessage));
    }

    Set<Pair<String, CiphertextMessage>> aliceOutOfOrderMessages = new HashSet<>();

    for (int i=0;i<10;i++) {
      String loopingMessage = ("What do we mean by saying that existence precedes essence? " +
                               "We mean that man first of all exists, encounters himself, " +
                               "surges up in the world--and defines himself aftward. " + i);
      CiphertextMessage aliceLoopingMessage = aliceSessionCipher.encrypt(loopingMessage.getBytes());

      aliceOutOfOrderMessages.add(new Pair<>(loopingMessage, aliceLoopingMessage));
    }

    for (int i=0;i<10;i++) {
      String loopingMessage = ("What do we mean by saying that existence precedes essence? " +
                               "We mean that man first of all exists, encounters himself, " +
                               "surges up in the world--and defines himself aftward. " + i);
      CiphertextMessage aliceLoopingMessage = aliceSessionCipher.encrypt(loopingMessage.getBytes());

      byte[] loopingPlaintext = bobSessionCipher.decrypt(aliceLoopingMessage.serialize());
      assertTrue(new String(loopingPlaintext).equals(loopingMessage));
    }

    for (int i=0;i<10;i++) {
      String loopingMessage = ("You can only desire based on what you know: " + i);
      CiphertextMessage bobLoopingMessage = bobSessionCipher.encrypt(loopingMessage.getBytes());

      byte[] loopingPlaintext = aliceSessionCipher.decrypt(bobLoopingMessage.serialize());
      assertTrue(new String(loopingPlaintext).equals(loopingMessage));
    }

    for (Pair<String, CiphertextMessage> aliceOutOfOrderMessage : aliceOutOfOrderMessages) {
      byte[] outOfOrderPlaintext = bobSessionCipher.decrypt(aliceOutOfOrderMessage.second().serialize());
      assertTrue(new String(outOfOrderPlaintext).equals(aliceOutOfOrderMessage.first()));
    }
  }

  private class InMemoryPreKey extends PreKeyRecord implements PreKey {

    private final IdentityKey identityKey;
    private final int         registrationId;

    public InMemoryPreKey(int keyId, ECKeyPair keyPair, IdentityKey identityKey, int registrationId) {
      super(keyId, keyPair);
      this.identityKey    = identityKey;
      this.registrationId = registrationId;
    }

    @Override
    public int getDeviceId() {
      return 1;
    }

    @Override
    public int getKeyId() {
      return getId();
    }

    @Override
    public ECPublicKey getPublicKey() {
      return getKeyPair().getPublicKey();
    }

    @Override
    public IdentityKey getIdentityKey() {
      return identityKey;
    }

    @Override
    public int getRegistrationId() {
      return registrationId;
    }
  }

}
