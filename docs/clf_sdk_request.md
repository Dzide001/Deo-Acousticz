# CLF reader tooling — access request

**Planning update, 2026-11-12:** this is an unsent historical request draft.
TAB import and experimental CF1/CF2 readers now exist, so this request does not
block all measured-directivity work. It remains useful for authoritative format
support and unresolved variants. See the current
[implementation plan](implementation_plan.md) for data and validation gates.

The [CLF FAQ](https://www.clfgroup.org/faq.htm) describes the format resolutions
and optional CLF2 v2 phase, filter and multipart data. Confirm current access
arrangements directly; the old statements about freely available specifications
and reader tooling should not be treated as a verified current offer.

**Status: not yet sent.** Draft below for you to send — I have not contacted
anyone on your behalf.

Contact point: the address listed on <http://www.clfgroup.org/> (check the
current site; historically `info@clfgroup.org`).

---

## Historical draft

Update the description of the existing readers before sending this draft.

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
   Confirm the representation of every supported symmetry and arc-order variant;
   the early corpus failure counts are superseded by later decoding work.
2. **Balloon offset** — is it computed from the metadata layout, or is there a
   directory structure that was missed? No header field holds it.
3. **Multipart files, phase data, filter blocks** — CLF2 v2 features, entirely
   unexplored here.
4. **Redistribution** — what permissions apply to bundling original or converted
   measurements, including portable user project packages? The development
   corpus has already been removed from app assets; the old APK size concern
   is historical, not a reason to bundle it again.
5. **Attribution** — what credit do the manufacturer and the measuring party
   require in-app?

## If they decline or do not reply

Continue with documented TAB support and the explicitly tested subset of binary
files. One matching TAB/binary pair does not validate every manufacturer or
optional feature. Preserve unresolved capability flags and reject unsupported
variants rather than labeling a guessed model as measured data.
