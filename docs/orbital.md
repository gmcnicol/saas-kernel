# Orbital publication

`@Published` marks safe publication roots for models, ordinary Services, and Action Services. Generation computes their semantic dependency closure. An unpublished referenced type, or another unpublished declaration sharing a selected source file, fails generation with its Taxi location.

The deterministic bundle is packaged at `META-INF/saas-kernel/publication`. Its manifest records exact Taxi sources, imported coordinates and resource checksums, qualified type definition checksums, Service kind, generator version, Taxi compiler version, and a bundle checksum. Imported definitions keep their Taxi qualified identity while each Application generates its own Java bindings.

Ordinary Services remain descriptions. Action Services describe capabilities only. Neither produces an HTTP endpoint, direct handler route, authority metadata, registry registration, or Orbital runtime dependency. Published Actions still execute only through Evaluation Snapshot, Cedar-authorised Action Offer, accepted Intent, handler, and ordered Event projection.

Orbital may consume the packaged bundle out of process. Kernel does not federate schemas at runtime.
