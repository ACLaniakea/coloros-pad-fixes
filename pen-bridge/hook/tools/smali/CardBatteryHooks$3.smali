.class Lcom/aclaniakea/colorosporttuning/CardBatteryHooks$3;
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

    .line 101
    invoke-direct {p0}, Lde/robv/android/xposed/XC_MethodHook;-><init>()V

    return-void
.end method


# virtual methods
.method protected beforeHookedMethod(Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;)V
    .registers 10
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Throwable;
        }
    .end annotation

    .line 105
    :try_start_0
    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->args:[Ljava/lang/Object;

    if-eqz v0, :cond_5c

    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->args:[Ljava/lang/Object;

    array-length v0, v0

    if-eqz v0, :cond_5c

    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->args:[Ljava/lang/Object;

    const/4 v1, 0x0

    aget-object v0, v0, v1

    if-nez v0, :cond_11

    goto :goto_5c

    .line 108
    :cond_11
    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->args:[Ljava/lang/Object;

    aget-object v0, v0, v1

    .line 109
    iget-object v2, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->thisObject:Ljava/lang/Object;

    invoke-static {v2}, Lcom/aclaniakea/colorosporttuning/HookUtils;->context(Ljava/lang/Object;)Landroid/content/Context;

    move-result-object v2

    if-nez v2, :cond_1e

    return-void

    .line 113
    :cond_1e
    invoke-static {v2}, Lcom/aclaniakea/colorosporttuning/HookUtils;->state(Landroid/content/Context;)Lcom/aclaniakea/colorosporttuning/PenState;

    move-result-object v2

    if-eqz v2, :cond_5c

    .line 114
    iget v3, v2, Lcom/aclaniakea/colorosporttuning/PenState;->battery:I

    if-gez v3, :cond_29

    goto :goto_5c

    .line 117
    :cond_29
    invoke-virtual {v0}, Ljava/lang/Object;->getClass()Ljava/lang/Class;

    move-result-object v3

    .line 118
    const-string v4, "value"

    invoke-virtual {v3, v4}, Ljava/lang/Class;->getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;

    move-result-object v4

    const/4 v5, 0x1

    .line 119
    invoke-virtual {v4, v5}, Ljava/lang/reflect/Field;->setAccessible(Z)V

    .line 120
    invoke-virtual {v4, v0}, Ljava/lang/reflect/Field;->getInt(Ljava/lang/Object;)I

    move-result v6

    .line 121
    iget v7, v2, Lcom/aclaniakea/colorosporttuning/PenState;->battery:I

    if-eq v6, v7, :cond_40

    return-void

    .line 124
    :cond_40
    iget v6, v2, Lcom/aclaniakea/colorosporttuning/PenState;->battery:I

    invoke-virtual {v4, v0, v6}, Ljava/lang/reflect/Field;->setInt(Ljava/lang/Object;I)V

    .line 125
    const-string v4, "charge"

    invoke-virtual {v3, v4}, Ljava/lang/Class;->getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;

    move-result-object v3

    .line 126
    invoke-virtual {v3, v5}, Ljava/lang/reflect/Field;->setAccessible(Z)V

    .line 127
    iget v4, v2, Lcom/aclaniakea/colorosporttuning/PenState;->charging:I

    if-eqz v4, :cond_53

    const/4 v1, 0x1

    :cond_53
    invoke-virtual {v3, v0, v1}, Ljava/lang/reflect/Field;->setBoolean(Ljava/lang/Object;Z)V

    .line 128
    iget-object p1, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->thisObject:Ljava/lang/Object;

    # invokes: Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->fixCardDisplay(Ljava/lang/Object;Lcom/aclaniakea/colorosporttuning/PenState;)V
    invoke-static {p1, v2}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->access$200(Ljava/lang/Object;Lcom/aclaniakea/colorosporttuning/PenState;)V
    :try_end_5b
    .catchall {:try_start_0 .. :try_end_5b} :catchall_5d

    goto :goto_6f

    :cond_5c
    :goto_5c
    return-void

    :catchall_5d
    move-exception p1

    .line 130
    new-instance v0, Ljava/lang/StringBuilder;

    const-string v1, "CardBatteryHooks setBatteryInfo: "

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p1

    invoke-static {p1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    :goto_6f
    return-void
.end method
