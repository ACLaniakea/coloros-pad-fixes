# Build-only harness for the official OPlus/OnePlus custom zsmalloc source.
# The source files are fetched into a temporary directory by the local test
# command; this target is never loaded on the tablet.

obj-m := oplus_bsp_zsmalloc_experimental.o
oplus_bsp_zsmalloc_experimental-y := zsmalloc.o

# The 8-page chain used by the 12 GB reference phone is not assumed safe for
# the 8 GB SM8650Q tablet.  The first compile gate uses the GKI baseline's
# 4-page chain; changing this is a separate experiment.
ccflags-y += -DCONFIG_ZSMALLOC_CHAIN_SIZE=4
