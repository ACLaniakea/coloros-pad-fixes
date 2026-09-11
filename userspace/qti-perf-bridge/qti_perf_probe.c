// SPDX-License-Identifier: Apache-2.0
/*
 * Read-only accessibility probe for QTI perf2 Binder service.
 *
 * It deliberately performs only AIDL getInterfaceVersion (transaction
 * 16777215).  It never sends perfLockAcquire/perfHint or changes a perf lock.
 * A future arbiter must pass this probe from the intended Magisk/KernelSU
 * process context before attempting to own any QTI performance resource.
 */

#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>
#include <dlfcn.h>
#include <stdio.h>

typedef AIBinder *(*check_service_fn)(const char *instance);

/* Required by AIBinder_Class_define even though this probe only owns a remote
 * proxy and never receives a transaction. */
static binder_status_t unused_on_transact(AIBinder *binder, transaction_code_t code,
		const AParcel *in, AParcel *out)
{
	(void)binder;
	(void)code;
	(void)in;
	(void)out;
	return STATUS_UNKNOWN_TRANSACTION;
}

#define QTI_PERF_SERVICE "vendor.qti.hardware.perf2.IPerf/default"
#define QTI_PERF_DESCRIPTOR "vendor.qti.hardware.perf2.IPerf"
#define AIDL_GET_INTERFACE_VERSION 16777215

int main(void)
{
	AIBinder *service;
	AIBinder_Class *service_class;
	AParcel *in = NULL;
	AParcel *out = NULL;
	AStatus *remote_status = NULL;
	void *binder_ndk = NULL;
	check_service_fn check_service = NULL;
	binder_status_t status;
	int32_t version = -1;
	const char *stage = "initialization";

	/* The bundled NDK is missing binder_manager.h/stubs, although API 36's
	 * libbinder_ndk exports this stable function.  Resolve it at runtime so the
	 * probe remains buildable with the trimmed toolchain. */
	binder_ndk = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
	check_service = binder_ndk ? (check_service_fn)dlsym(binder_ndk,
		"AServiceManager_checkService") : NULL;
	if (!check_service) {
		fprintf(stderr, "AServiceManager_checkService unavailable\n");
		if (binder_ndk)
			dlclose(binder_ndk);
		return 3;
	}

	stage = "service lookup";
	service = check_service(QTI_PERF_SERVICE);
	if (!service) {
		fprintf(stderr, "service unavailable: %s\n", QTI_PERF_SERVICE);
		dlclose(binder_ndk);
		return 2;
	}
	service_class = AIBinder_Class_define(QTI_PERF_DESCRIPTOR, NULL, NULL,
		unused_on_transact);
	if (!service_class || !AIBinder_associateClass(service, service_class)) {
		fprintf(stderr, "cannot associate QTI IPerf AIDL descriptor\n");
		AIBinder_decStrong(service);
		dlclose(binder_ndk);
		return 4;
	}

	stage = "prepare transaction";
	status = AIBinder_prepareTransaction(service, &in);
	if (status != STATUS_OK)
		goto fail;
	stage = "getInterfaceVersion transaction";
	status = AIBinder_transact(service, AIDL_GET_INTERFACE_VERSION, &in, &out, 0);
	/* transact consumes the input parcel and clears in. */
	if (status != STATUS_OK)
		goto fail;
	stage = "read remote status";
	status = AParcel_readStatusHeader(out, &remote_status);
	if (status != STATUS_OK || !AStatus_isOk(remote_status))
		goto fail;
	stage = "read interface version";
	status = AParcel_readInt32(out, &version);
	if (status != STATUS_OK)
		goto fail;

	printf("QTI IPerf reachable; interface_version=%d\n", version);
	AStatus_delete(remote_status);
	AParcel_delete(out);
	AIBinder_decStrong(service);
	dlclose(binder_ndk);
	return 0;

fail:
	fprintf(stderr, "QTI IPerf read-only probe failed at %s: binder_status=%d\n",
		stage, status);
	if (remote_status)
		AStatus_delete(remote_status);
	if (out)
		AParcel_delete(out);
	if (in)
		AParcel_delete(in);
	AIBinder_decStrong(service);
	dlclose(binder_ndk);
	return 1;
}
