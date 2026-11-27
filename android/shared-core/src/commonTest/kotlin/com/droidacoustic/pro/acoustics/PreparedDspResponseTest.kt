package com.droidacoustic.pro.acoustics

import kotlin.test.*

class PreparedDspResponseTest {
    @Test fun distinctRowsStayWithTheirSourcesAcrossReorderAndFilterEdit() {
        val first=DspFilterChain(listOf(DspFilter(FilterKind.PEAK,1000.0,2.0,6.0)))
        val second=DspFilterChain(listOf(DspFilter(FilterKind.PEAK,1000.0,2.0,-6.0)))
        val copied=DspFilterChain.fromJsonString(first.toJsonString())
        val frequencies=doubleArrayOf(800.0,1000.0,1200.0)
        val rows=FilterResponseRows.prepare(frequencies,listOf(first,second,copied))
        assertSame(rows[0],rows[2]);assertNotSame(rows[0],rows[1])
        assertNotEquals(rows[0]!![0],rows[1]!![0]) // Includes distinct complex phase.
        val edited=DspFilterChain(second.filters.map {it.copy(gainDb=3.0)})
        val next=FilterResponseRows.prepare(frequencies,listOf(second,edited,first))
        for(i in frequencies.indices) {
            assertEquals(second.at(frequencies[i]),next[0]!![i])
            assertEquals(edited.at(frequencies[i]),next[1]!![i])
            assertEquals(first.at(frequencies[i]),next[2]!![i])
            assertEquals(second.at(frequencies[i]),rows[1]!![i])
        }
    }
    @Test fun missingNyquistSamplesStayLocalToEachArrayRate() {
        val chains=listOf(32000.0,48000.0,96000.0).map {rate ->
            DspFilterChain(listOf(DspFilter(FilterKind.LOW_PASS,1000.0,sampleRateHz=rate)))
        }+DspFilterChain()
        val frequencies=doubleArrayOf(1000.0,16000.0,24000.0,30000.0)
        val rows=FilterResponseRows.prepare(frequencies,chains)
        for(source in 0..2) for(i in frequencies.indices)
            assertEquals(chains[source].at(frequencies[i]),rows[source]!![i])
        assertNull(rows[0]!![1]);assertNotNull(rows[1]!![1])
        assertNull(rows[1]!![2]);assertNotNull(rows[2]!![2]);assertNull(rows[3])
    }
    @Test fun sharedTrigonometryMatchesOrderedIndividualSectionsAcrossRateChanges() {
        val sections=listOf(DspFilter(FilterKind.HIGH_PASS,80.0),
            DspFilter(FilterKind.PEAK,1000.0,2.0,-3.0),
            DspFilter(FilterKind.LOW_PASS,12000.0,sampleRateHz=96000.0),
            DspFilter(FilterKind.PEAK,4000.0,7.0,6.0))
        val chain=DspFilterChain(sections)
        for(hz in listOf(0.0,1e-9,10.0,80.0,1000.0,4000.0,15999.0,23999.999,24000.0,30000.0)) {
            var expected:ComplexPressure?=FilterResponseRows.IDENTITY
            for(filter in sections) {val h=filter.at(hz);expected=if(expected==null || h==null) null else expected*h}
            assertEquals(expected,chain.at(hz),"frequency=$hz")
        }
    }
    @Test fun copiedAndReopenedFiltersRebuildCoefficientsWithoutChangingIdentityOrPhase() {
        val first=DspFilter(FilterKind.PEAK,1000.0,2.0,6.0)
        val changed=first.copy(frequencyHz=2500.0,q=4.0,gainDb=-5.0,sampleRateHz=96000.0)
        assertNotEquals(first.at(1000.0),changed.at(1000.0))
        val chain=DspFilterChain(listOf(first,changed))
        val reopened=DspFilterChain.fromJsonString(chain.toJsonString())
        assertEquals(chain,reopened);assertEquals(chain.hashCode(),reopened.hashCode())
        for(hz in listOf(0.0,63.0,1000.0,2500.0,8000.0,23000.0)) assertEquals(chain.at(hz),reopened.at(hz))
        assertEquals(changed,DspFilter(FilterKind.PEAK,2500.0,4.0,-5.0,96000.0))
    }
    @Test fun invalidFrequenciesRemainMissingAndEmptyChainsRetainIdentity() {
        val low=DspFilterChain(listOf(DspFilter(FilterKind.LOW_PASS,1000.0,sampleRateHz=32000.0)))
        val high=DspFilterChain(listOf(DspFilter(FilterKind.LOW_PASS,1000.0,sampleRateHz=96000.0)))
        for(hz in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,16000.0,30000.0)) assertNull(low.at(hz))
        assertNotNull(high.at(30000.0))
        for(hz in listOf(Double.NaN,-1.0,30000.0)) assertEquals(FilterResponseRows.IDENTITY,DspFilterChain().at(hz))
    }
}
