package com.airdrop.android.daemon

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun parse(json: String) = parseEvent(JSONObject(json))

    @Test fun onlyWebLinksAreOpenable() {
        assertTrue(isWebUrl("https://example.com/a?b=c"))
        assertTrue(isWebUrl("HTTP://example.com"))
        for (bad in listOf(
            "intent://x#Intent;scheme=http;end", "file:///sdcard/a", "content://media/1", "tel:123",
            "javascript:alert(1)", "https://a.com b", " https://a.com", "https://", "", "ftp://a.com",
        )) assertFalse(bad, isWebUrl(bad))
    }

    @Test fun peersGetCleanNamesAndAFallback() {
        val e = parse("""{"ev":"peers","peers":[{"mac":"aa","name":" \"Ann's iPhone\" ","model":"iPhone"},{"mac":"bb","name":""}]}""") as Event.Peers
        assertEquals(listOf(Peer("aa", "Ann's iPhone", "iPhone"), Peer("bb", "Apple device", "")), e.peers)
    }

    @Test fun transferStatesMapAndUnknownIsFailed() {
        val sending = (parse("""{"ev":"transfer","mac":"airdrop:aa","state":"sending","sent":5,"total":10}""") as Event.Transfer).out
        assertEquals(Outgoing("aa", SendState.Sending, 5, 10), sending)
        assertEquals(0.5f, sending.fraction, 0f)
        assertTrue(sending.active)
        val odd = (parse("""{"ev":"transfer","mac":"aa","state":"exploded"}""") as Event.Transfer).out
        assertEquals(SendState.Failed, odd.state)
        assertFalse(odd.active)
    }

    @Test fun fractionIsBoundedAndSafeWithoutATotal() {
        assertEquals(0f, Outgoing("aa", SendState.Sending, 5, 0).fraction, 0f)
        assertEquals(1f, Outgoing("aa", SendState.Sending, 50, 10).fraction, 0f)
    }

    @Test fun incomingAskCarriesFilesAndLinks() {
        val i = (parse("""{"ev":"incoming","id":7,"from":"'Bob'","files":["a.jpg"],"urls":["https://x.y"],"total":9}""") as Event.IncomingAsk).incoming
        assertEquals(7L, i.id)
        assertEquals("Bob", i.from)
        assertEquals(2, i.count)
        assertNull(i.singleName)
        assertEquals(ReceiveState.Asking, i.state)
    }

    @Test fun eventsWithoutTheirIdAreRejected() {
        for (ev in listOf("incoming", "receiving", "received", "urls", "withdrawn", "failed")) {
            try {
                parse("""{"ev":"$ev"}""")
                fail("$ev parsed without an id")
            } catch (_: org.json.JSONException) { }
        }
    }

    @Test fun statusReadsCoexistenceAndModuleState() {
        val s = parse("""{"ev":"status","state":"on","oshare":1,"coex":{"active":true,"reason":null},"module_state":"on"}""") as Event.Status
        assertTrue(s.on); assertFalse(s.failed); assertFalse(s.starting)
        assertTrue(s.coexActive); assertTrue(s.coexWanted); assertTrue(s.coexAvailable)
        assertEquals("", s.coexReason)
        assertTrue(s.moduleActive)
        val bare = parse("""{"ev":"status","state":"starting"}""") as Event.Status
        assertTrue(bare.starting)
        assertFalse(bare.coexAvailable)
    }

    @Test fun unknownEventsAreIgnored() {
        assertEquals(Event.Unknown, parse("""{"ev":"something-new"}"""))
        assertEquals(Event.Unknown, parse("{}"))
    }

    @Test fun onlySupportedModelsGetARadio() {
        for (m in listOf("OPD2508", "opd2508", "PKT110", "PLB110")) {
            assertTrue(m, RadioInstaller.isMt6991(m))
            assertEquals("radio-mt6991.zip", RadioInstaller.assetForModel(m))
        }
        for (m in listOf("PKX110", "PJZ110", "", "OPD2508X")) {
            assertFalse(m, RadioInstaller.isMt6991(m))
            assertNull(RadioInstaller.assetForModel(m))
        }
    }
}
