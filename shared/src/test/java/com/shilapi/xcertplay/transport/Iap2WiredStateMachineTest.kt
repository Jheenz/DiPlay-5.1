package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2AuthenticationMessages
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.session.Iap2MessageSession
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationException
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import org.junit.Assert.*
import org.junit.Test

class Iap2WiredStateMachineTest {
    private class ScriptedSession(vararg frames: Iap2Frame, private val closedWhenDrained: Boolean = false) : Iap2MessageSession {
        private val incoming = ArrayDeque(frames.toList())
        val sent = mutableListOf<Iap2Frame>()
        var readyCalls = 0

        override val isClosed: Boolean get() = closedWhenDrained && incoming.isEmpty()
        override fun awaitReady(timeoutMillis: Long): Boolean { readyCalls++; return true }
        override fun send(frame: Iap2Frame, timeoutMillis: Long) { sent += frame }
        override fun recv(timeoutMillis: Long): Iap2Frame? = incoming.removeFirstOrNull()
    }

    private class MockAuthenticator : MfiAuthenticator {
        val certificate = byteArrayOf(1, 2, 3, 4)
        val signature = byteArrayOf(9, 8, 7)
        val signedChallenges = mutableListOf<ByteArray>()
        var certificateReads = 0

        override val certificateType = MfiCertificateType.MFI
        override fun protocolMajor() = 3
        override fun readCertificate(maximumOutputLength: Int): ByteArray {
            certificateReads++
            return certificate.copyOf()
        }
        override fun signChallenge(challenge: ByteArray): ByteArray {
            signedChallenges += challenge.copyOf()
            return signature.copyOf()
        }
    }

    @Test fun identificationCompletesOnlyAfterStartAndInformationWasSent() {
        val session = ScriptedSession(
            Iap2Frame(Iap2IdentificationClient.START_IDENTIFICATION, ByteArray(0)),
            Iap2Frame(Iap2IdentificationClient.IDENTIFICATION_ACCEPTED, ByteArray(0)),
        )
        Iap2IdentificationClient(session).identify(identificationConfig())
        assertEquals(1, session.readyCalls)
        assertEquals(listOf(Iap2IdentificationClient.IDENTIFICATION_INFORMATION), session.sent.map { it.messageId })

        val prematureAccept = ScriptedSession(Iap2Frame(Iap2IdentificationClient.IDENTIFICATION_ACCEPTED, ByteArray(0)))
        assertThrows(Iap2IdentificationException.UnexpectedMessage::class.java) {
            Iap2IdentificationClient(prematureAccept).identify(identificationConfig())
        }
        assertTrue(prematureAccept.sent.isEmpty())
    }

    @Test fun identificationRejectAndMalformedResponseFailClosed() {
        val rejectedFrame = Iap2Frame(
            Iap2IdentificationClient.IDENTIFICATION_REJECTED,
            Iap2ParameterList.of(Iap2Parameter(4, byteArrayOf(1))).encode(),
        )
        val rejectedSession = ScriptedSession(
            Iap2Frame(Iap2IdentificationClient.START_IDENTIFICATION, ByteArray(0)), rejectedFrame,
        )
        val rejected = assertThrows(Iap2IdentificationException.Rejected::class.java) {
            Iap2IdentificationClient(rejectedSession).identify(identificationConfig())
        }
        assertEquals(setOf(4), rejected.parameterIds)
        assertEquals(1, rejectedSession.sent.size)

        val malformedSession = ScriptedSession(
            Iap2Frame(Iap2IdentificationClient.START_IDENTIFICATION, ByteArray(0)),
            Iap2Frame(Iap2IdentificationClient.IDENTIFICATION_REJECTED, byteArrayOf(0, 1)),
        )
        assertThrows(Exception::class.java) {
            Iap2IdentificationClient(malformedSession).identify(identificationConfig())
        }
    }

    @Test fun mfiMockProviderCompletesCertificateChallengeResponseAndSuccessSequence() {
        val challenge = byteArrayOf(3, 1, 4, 1)
        val provider = MockAuthenticator()
        val session = ScriptedSession(
            Iap2Frame(0xaa00, ByteArray(0)),
            challengeFrame(challenge),
            Iap2Frame(0xaa05, ByteArray(0)),
        )

        Iap2MfiAuthenticationClient(provider).run(session)

        assertEquals(1, provider.certificateReads)
        assertEquals(1, provider.signedChallenges.size)
        assertArrayEquals(challenge, provider.signedChallenges.single())
        assertEquals(listOf(0xaa01, 0xaa03), session.sent.map { it.messageId })
        assertArrayEquals(provider.certificate, Iap2BodyReader.of(session.sent[0]).bytes(0))
        assertArrayEquals(provider.signature, Iap2BodyReader.of(session.sent[1]).bytes(0))
    }

    @Test fun mfiSuccessBeforeChallengeResponseIsRejected() {
        val provider = MockAuthenticator()
        val session = ScriptedSession(
            Iap2Frame(0xaa00, ByteArray(0)),
            Iap2Frame(0xaa05, ByteArray(0)),
        )
        assertThrows(Iap2MfiAuthenticationException::class.java) {
            Iap2MfiAuthenticationClient(provider).run(session)
        }
        assertEquals(listOf(0xaa01), session.sent.map { it.messageId })
        assertTrue(provider.signedChallenges.isEmpty())
    }

    @Test fun malformedChallengeAuthenticationFailureUnexpectedMessageAndTimeoutFailClosed() {
        val malformedProvider = MockAuthenticator()
        val malformed = ScriptedSession(Iap2Frame(0xaa00, ByteArray(0)), Iap2Frame(0xaa02, ByteArray(0)))
        assertThrows(Iap2MfiAuthenticationException::class.java) {
            Iap2MfiAuthenticationClient(malformedProvider).run(malformed)
        }
        assertEquals(0, malformedProvider.signedChallenges.size)
        assertEquals(listOf(0xaa01), malformed.sent.map { it.messageId })

        for (terminal in listOf(0xaa04, 0x7777)) {
            val session = ScriptedSession(Iap2Frame(0xaa00, ByteArray(0)), Iap2Frame(terminal, ByteArray(0)))
            assertThrows(Iap2MfiAuthenticationException::class.java) {
                Iap2MfiAuthenticationClient(MockAuthenticator()).run(session)
            }
        }
        assertThrows(Iap2MfiAuthenticationException::class.java) {
            Iap2MfiAuthenticationClient(MockAuthenticator()).run(ScriptedSession(), timeoutMillis = 1)
        }
    }

    @Test fun wiredControlSequenceRunsIdentificationThenMockMfiThenSubscriptionsAndAvailability() {
        val challenge = byteArrayOf(5, 4, 3, 2, 1)
        val session = ScriptedSession(
            Iap2Frame(Iap2IdentificationClient.START_IDENTIFICATION, ByteArray(0)),
            Iap2Frame(Iap2IdentificationClient.IDENTIFICATION_ACCEPTED, ByteArray(0)),
            Iap2Frame(0xaa00, ByteArray(0)),
            challengeFrame(challenge),
            Iap2Frame(0xaa05, ByteArray(0)),
            Iap2Frame(0x7777, byteArrayOf(4, 5)),
            Iap2Messages.buildRaw(0x4300) { group(0) { u8(0, 1) } },
            closedWhenDrained = true,
        )
        val progress = mutableListOf<String>()
        val forwarded = mutableListOf<Iap2Frame>()
        val result = Iap2WiredControlClient(session, Iap2MfiAuthenticationClient(MockAuthenticator())).run(
            identification = identificationConfig(),
            endpoint = Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf("fe80::2"),
                airPlayPort = 7000,
                publicKey = "synthetic-public-key",
                sourceVersion = "offline-test",
            ),
            availableCurrentMilliAmps = 2400,
            timeoutMillis = 5_000,
            onProgress = progress::add,
            onIncoming = forwarded::add,
        )

        val sentIds = session.sent.map { it.messageId }
        assertEquals(Iap2IdentificationClient.IDENTIFICATION_INFORMATION, sentIds[0])
        assertEquals(0xaa01, sentIds[1])
        assertEquals(0xaa03, sentIds[2])
        assertEquals(Iap2ControlMessages.powerSourceUpdate(2400, charging = true).messageId, sentIds[3])
        assertEquals(Iap2ControlMessages.subscriptions().map { it.messageId }, sentIds.subList(4, 9))
        assertEquals(0x4301, sentIds[9])
        assertEquals(Iap2WiredControlTerminal.CHANNEL_CLOSED, result.terminal)
        assertEquals(Iap2WiredControlStage.CARPLAY_START_SENT, result.stage)
        assertEquals(1, result.carPlayStartSessionsSent)
        assertEquals(listOf(0x7777), forwarded.map { it.messageId })
        assertTrue(progress.contains("iap2 identification accepted"))
        assertTrue(progress.contains("iap2 authentication accepted"))
    }

    private fun challengeFrame(challenge: ByteArray) = Iap2Frame(
        0xaa02,
        Iap2ParameterList.of(Iap2Parameter(0, challenge)).encode(),
    )

    private fun identificationConfig() = Iap2IdentificationConfig(
        name = "offline-test",
        modelIdentifier = "offline-test",
        manufacturer = "offline-test",
        serialNumber = "offline-test-1",
        firmwareVersion = "1",
        hardwareVersion = "1",
        carPlayUsbInterfaceNumber = 3,
    )
}
