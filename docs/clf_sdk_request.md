# CLF reader tooling — access request

Phase 2 (real measured directivity) is blocked on this. The CLF Group states
that the binary format definition is freely available and that creation/reading
tools are provided on request: <http://www.clfgroup.org/faq.htm>.

**Status: not yet sent.** Draft below for you to send — I have not contacted
anyone on your behalf.

Contact point: the address listed on <http://www.clfgroup.org/> (check the
current site; historically `info@clfgroup.org`).

---

## Draft

> Subject: Request for CLF reader tooling / CF1–CF2 format definition
>
> Hello,
>
> I am developing DroidAcoustic Pro, an open-source acoustic prediction
> application for Android tablets, in the same family of tools as EASE,
> ArrayCalc and Soundvision. It models loudspeaker coverage, SPL distribution
> and intelligibility for system design.
>
> I would like to read CLF data properly rather than approximate it. My
> application currently substitutes synthetic directivity patterns, which I am
> not willing to ship as if they were measured data.
>
> I am writing to request:
>
> 1. The CF1/CF2 format definition, and
> 2. Any reader library or SDK you make available for it.
>
> I am working from loudspeaker data files published by manufacturers through
> clfgroup.org. My intention is to display and simulate that data with the
> measurements intact and correctly attributed to the manufacturer and the
> measuring party, and to respect whatever redistribution terms apply to the
> data files themselves.
>
> Could you let me know what is required — licence terms, attribution, any
> restriction on redistributing decoded data within an application, and whether
> open-source distribution changes any of that?
>
> Happy to provide more detail about the project.
>
> Thank you,
> Constant

---

## Questions worth getting answered while you are asking

These all affect the design, and guessing at them is how projects end up
shipping something they have to tear out later:

1. **`<BALLOON-SYMMETRY>`** — how are quarter/half symmetric balloons stored?
   This is the most likely reason ~480 of the 679 bundled files do not decode
   with what has been worked out so far.
2. **Balloon offset** — is it computed from the metadata layout, or is there a
   directory structure that was missed? No header field holds it.
3. **Multipart files, phase data, filter blocks** — CLF2 v2 features, entirely
   unexplored here.
4. **Redistribution** — may decoded directivity be bundled inside an application,
   and in a converted/compressed form? This decides whether the 430 MB corpus
   can be pre-converted to something shippable, which it has to be either way:
   the debug APK is currently 317 MB against Google Play's 150 MB base limit.
5. **Attribution** — what credit do the manufacturer and the measuring party
   require in-app?

## If they decline or do not reply

The reverse-engineering in `docs/clf_format_notes.md` is verified against
published ground truth and would be enough to proceed, with the symmetry and
offset questions still to solve. That is a fallback, not the plan of record.
