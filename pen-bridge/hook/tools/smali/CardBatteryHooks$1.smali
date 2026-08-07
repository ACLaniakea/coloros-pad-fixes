.class Lcom/aclaniakea/colorosporttuning/CardBatteryHooks$1;
.super Lde/robv/android/xposed/XC_MethodHook;
.source "CardBatteryHooks.java"


# annotations
.annotation system Ldalvik/annotation/EnclosingMethod;
    value = Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->install(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x0
    name = null
.end annotation


# direct methods
.method constructor <init>()V
    .registers 1

    .line 35
    invoke-direct {p0}, Lde/robv/android/xposed/XC_MethodHook;-><init>()V

    return-void
.end method


# virtual methods
.method protected afterHookedMethod(Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;)V
    .registers 6
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Throwable;
        }
    .end annotation

    .line 39
    :try_start_0
    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->thisObject:Ljava/lang/Object;

    if-nez v0, :cond_5

    return-void

    .line 43
    :cond_5
    const-string v1, "getName"

    const/4 v2, 0x0

    new-array v3, v2, [Ljava/lang/Object;

    invoke-static {v0, v1, v3}, Lcom/aclaniakea/colorosporttuning/HookUtils;->call(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v1

    invoke-static {v1}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;

    move-result-object v1

    .line 44
    invoke-virtual {v1}, Ljava/lang/String;->toLowerCase()Ljava/lang/String;

    move-result-object v1

    .line 45
    const-string v3, "lenovo"

    invoke-virtual {v1, v3}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z

    move-result v3

    if-eqz v3, :cond_44

    const-string v3, "pen"

    invoke-virtual {v1, v3}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z

    move-result v1

    if-nez v1, :cond_27

    goto :goto_44

    .line 48
    :cond_27
    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->context(Ljava/lang/Object;)Landroid/content/Context;

    move-result-object v0

    if-nez v0, :cond_2e

    return-void

    .line 52
    :cond_2e
    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->state(Landroid/content/Context;)Lcom/aclaniakea/colorosporttuning/PenState;

    move-result-object v0

    if-nez v0, :cond_35

    return-void

    .line 56
    :cond_35
    iget v1, v0, Lcom/aclaniakea/colorosporttuning/PenState;->battery:I

    iget v0, v0, Lcom/aclaniakea/colorosporttuning/PenState;->charging:I

    if-eqz v0, :cond_3c

    const/4 v2, 0x1

    :cond_3c
    # invokes: Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->buildBatteryInfo(IZ)Ljava/lang/Object;
    invoke-static {v1, v2}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->access$000(IZ)Ljava/lang/Object;

    move-result-object v0

    invoke-virtual {p1, v0}, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->setResult(Ljava/lang/Object;)V
    :try_end_43
    .catchall {:try_start_0 .. :try_end_43} :catchall_45

    goto :goto_57

    :cond_44
    :goto_44
    return-void

    :catchall_45
    move-exception p1

    .line 58
    new-instance v0, Ljava/lang/StringBuilder;

    const-string v1, "CardBatteryHooks battery: "

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p1

    invoke-static {p1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    :goto_57
    return-void
.end method
