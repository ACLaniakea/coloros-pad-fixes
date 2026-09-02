# ZUI Camera native identity experiment

This is an independent, target-scoped Zygisk experiment for `com.zui.camera`.
It does not use Device Faker, modify `build.prop`, hook libc, or alter any
property page shared by other processes. At app specialization it remaps only
the property pages containing four existing identity values as private COW
pages, preserving their virtual addresses for bionic's property context.

The first test deliberately limits the map to the explicit native requirement
seen in the Morpho binaries plus the matching Java identity:

- `ro.product.manufacturer=Lenovo`
- `ro.product.brand=Lenovo`
- `ro.product.model=TB132`
- `ro.product.device=TB710FU`

It leaves fingerprints, security properties, and every other process unchanged.
The module must be validated by the `ZuiNativeIdentity` log before it is allowed
to affect the portrait-tracking experiment.
