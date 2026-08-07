.class Lcom/aclaniakea/colorosporttuning/IpeManagerHooks$46;
.super Lde/robv/android/xposed/XC_MethodHook;
.source "IpeManagerHooks.java"


# annotations
.annotation system Ldalvik/annotation/EnclosingMethod;
    value = Lcom/aclaniakea/colorosporttuning/IpeManagerHooks;->installMyDevicesStateBridge(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x0
    name = null
.end annotation


# direct methods
.method constructor <init>()V
    .registers 1

    invoke-direct {p0}, Lde/robv/android/xposed/XC_MethodHook;-><init>()V

    return-void
.end method


# virtual methods
.method protected beforeHookedMethod(Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;)V
    .registers 5

    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->args:[Ljava/lang/Object;

    array-length v1, v0

    const/4 v2, 0x2

    if-le v1, v2, :cond_ret

    const/4 v1, 0x0

    aget-object v1, v0, v1

    invoke-static {v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->context(Ljava/lang/Object;)Landroid/content/Context;

    move-result-object v1

    if-eqz v1, :cond_ret

    invoke-static {v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->state(Landroid/content/Context;)Lcom/aclaniakea/colorosporttuning/PenState;

    move-result-object v1

    if-eqz v1, :cond_ret

    iget-boolean v2, v1, Lcom/aclaniakea/colorosporttuning/PenState;->connected:Z

    if-nez v2, :cond_ret

    const/4 v2, 0x2

    invoke-static {v2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v2

    const/4 v3, 0x1

    aput-object v2, v0, v3

    const-string v0, "IPe panel connect state forced connected"

    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    :cond_ret
    return-void
.end method
