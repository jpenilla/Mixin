/*
 * This file is part of Mixin, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.asm.mixin.transformer;

import java.lang.annotation.Annotation;
import java.util.Iterator;

import org.spongepowered.asm.logging.ILogger;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment.CompatibilityLevel;
import org.spongepowered.asm.mixin.MixinEnvironment.Option;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.extensibility.IActivityContext.IActivity;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.throwables.InvalidAccessorException;
import org.spongepowered.asm.mixin.struct.MemberRef;
import org.spongepowered.asm.mixin.throwables.ClassMetadataNotFoundException;
import org.spongepowered.asm.mixin.throwables.MixinException;
import org.spongepowered.asm.mixin.transformer.ClassInfo.Field;
import org.spongepowered.asm.mixin.transformer.ClassInfo.Method;
import org.spongepowered.asm.mixin.transformer.ClassInfo.SearchType;
import org.spongepowered.asm.mixin.transformer.ClassInfo.TypeLookup;
import org.spongepowered.asm.mixin.transformer.MixinInfo.MixinClassNode;
import org.spongepowered.asm.mixin.transformer.MixinInfo.MixinMethodNode;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.meta.MixinRenamed;
import org.spongepowered.asm.mixin.transformer.throwables.InvalidMixinException;
import org.spongepowered.asm.mixin.transformer.throwables.MixinPreProcessorException;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.asm.util.Annotations;
import org.spongepowered.asm.util.Bytecode;
import org.spongepowered.asm.util.Bytecode.Visibility;
import org.spongepowered.asm.util.Constants;
import org.spongepowered.asm.util.perf.Profiler;
import org.spongepowered.asm.util.perf.Profiler.Section;
import org.spongepowered.asm.util.throwables.SyntheticBridgeException;

import com.google.common.base.Strings;

/**
 * <p>Mixin bytecode pre-processor. This class is responsible for bytecode pre-
 * processing tasks required to be performed on mixin bytecode before the mixin
 * can be applied. It also performs some early sanity checking and validation on
 * the mixin.</p>
 * 
 * <p>Before a mixin can be applied to the target class, it is necessary to
 * convert certain aspects of the mixin bytecode into the intended final form of
 * the mixin, this involves for example stripping the prefix from shadow and
 * soft-implemented methods. This preparation is done in two stages: first the
 * target-context-insensitive transformations are applied (this also acts as a
 * validation pass when the mixin is first loaded) and then transformations
 * which depend on the target class are applied in a second stage. This class
 * handles the first stage of transformations, and {@link MixinTargetContext}
 * handles the second.</p>
 * 
 * <p>The validation pass propagates method renames into the metadata tree and
 * thus changes made during this phase are visible to all other mixins. The
 * target-context-sensitive pass on the other hand can only operate on private
 * class members for obvious reasons.</p>  
 */
class MixinPreProcessorStandard {
    
    /**
     * Types of annotated special method handled by the preprocessor
     */
    enum SpecialMethod {
        
        MERGE(true),
        OVERWRITE(true, Overwrite.class),
        SHADOW(false, Shadow.class),
        ACCESSOR(false, Accessor.class),
        INVOKER(false, Invoker.class);
        
        final boolean isOverwrite;
        
        final Class<? extends Annotation> annotation;
        
        final String description;

        private SpecialMethod(boolean isOverwrite, Class<? extends Annotation> type) {
            this.isOverwrite = isOverwrite;
            this.annotation = type;
            this.description = "@" + Annotations.getSimpleName(type);
        }
        
        private SpecialMethod(boolean isOverwrite) {
            this.isOverwrite = isOverwrite;
            this.annotation = null;
            this.description = "overwrite";
        }
        
        @Override
        public String toString() {
            return this.description;
        }
        
    }
    
    /**
     * Logger
     */
    private static final ILogger logger = MixinService.getService().getLogger("mixin");

    /**
     * The mixin
     */
    protected final MixinInfo mixin;
    
    /**
     * Mixin class node
     */
    protected final MixinClassNode classNode;
    
    protected final MixinEnvironment env;
    
    protected final Profiler profiler = Profiler.getProfiler("mixin");
    
    protected final ActivityStack activities = new ActivityStack();

    private final boolean verboseLogging, strictUnique;
    
    private boolean prepared, attached;
    
    MixinPreProcessorStandard(MixinInfo mixin, MixinClassNode classNode) {
        this.mixin = mixin;
        this.classNode = classNode;
        this.env = mixin.getParent().getEnvironment();
        this.verboseLogging = this.env.getOption(Option.DEBUG_VERBOSE);
        this.strictUnique = this.env.getOption(Option.DEBUG_UNIQUE);
    }

    /**
     * Run the first pass. Propagates changes into the metadata tree.
     * 
     * @return Prepared classnode
     */
    final MixinPreProcessorStandard prepare(Extensions extensions) {
        if (this.prepared) {
            return this;
        }
        
        this.prepared = true;
        
        this.activities.clear();
        Section prepareTimer = this.profiler.begin("prepare");
        try {
            IActivity activity = this.activities.begin("Prepare inner classes");
            this.prepareInnerClasses(extensions);
            
            activity.next("Prepare method");
            for (MixinMethodNode mixinMethod : this.classNode.mixinMethods) {
                Method method = this.mixin.getClassInfo().findMethod(mixinMethod, mixinMethod.access | ClassInfo.INCLUDE_INITIALISERS);
                IActivity methodActivity = this.activities.begin(mixinMethod.toString());
                this.prepareMethod(mixinMethod, method);
                methodActivity.end();
            }
            activity.next("Prepare field");
            for (FieldNode mixinField : this.classNode.fields) {
                IActivity fieldActivity = this.activities.begin(String.format("%s:%s", mixinField.name, mixinField.desc));
                this.prepareField(mixinField);
                fieldActivity.end();
            }
            activity.end();
        } catch (MixinException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MixinPreProcessorException(String.format("Prepare error for %s during activity:", this.mixin), ex, this.activities);
        }
        prepareTimer.end();
        return this;
    }

    protected void prepareInnerClasses(Extensions extensions) {
        InnerClassGenerator icg = extensions.<InnerClassGenerator>getGenerator(InnerClassGenerator.class);
        for (String targetClassName : this.mixin.getDeclaredTargetClasses()) {
            ClassInfo targetClassInfo = ClassInfo.forName(targetClassName);
            for (String innerClass : this.mixin.getInnerClasses()) {
                icg.registerInnerClass(this.mixin, targetClassInfo, innerClass);
            }
        }
    }

    protected void prepareMethod(MixinMethodNode mixinMethod, Method method) {
        this.prepareShadow(mixinMethod, method);
        this.prepareSoftImplements(mixinMethod, method);
    }

    protected void prepareShadow(MixinMethodNode mixinMethod, Method method) {
        AnnotationNode shadowAnnotation = Annotations.getVisible(mixinMethod, Shadow.class);
        if (shadowAnnotation == null) {
            return;
        }
        
        String prefix = Annotations.<String>getValue(shadowAnnotation, "prefix", Shadow.class);
        if (mixinMethod.name.startsWith(prefix)) {
            Annotations.setVisible(mixinMethod, MixinRenamed.class, "originalName", mixinMethod.name);
            String newName = mixinMethod.name.substring(prefix.length());
            mixinMethod.name = method.renameTo(newName);
        }
    }

    protected void prepareSoftImplements(MixinMethodNode mixinMethod, Method method) {
        for (InterfaceInfo iface : this.mixin.getSoftImplements()) {
            if (iface.renameMethod(mixinMethod)) {
                method.renameTo(mixinMethod.name);
            }
        }
    }

    protected void prepareField(FieldNode mixinField) {
        // stub
    }
    
    final MixinPreProcessorStandard conform(TargetClassContext target) {
        return this.conform(target.getClassInfo());
    }
    
    final MixinPreProcessorStandard conform(ClassInfo target) {
        this.activities.clear();
        Section conformTimer = this.profiler.begin("conform");
        try {
            for (MixinMethodNode mixinMethod : this.classNode.mixinMethods) {
                if (mixinMethod.isInjector()) {
                    Method method = this.mixin.getClassInfo().findMethod(mixinMethod, ClassInfo.INCLUDE_ALL);
                    IActivity methodActivity = this.activities.begin("Conform injector %s", mixinMethod);
                    this.conformInjector(target, mixinMethod, method);
                    methodActivity.end();
                }
            }
        } catch (MixinException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MixinPreProcessorException(String.format("Conform error for %s during activity:", this.mixin), ex, this.activities);
        }
        conformTimer.end();
        return this;
    }
    
    private void conformInjector(ClassInfo targetClass, MixinMethodNode mixinMethod, Method method) {
        MethodMapper methodMapper = targetClass.getMethodMapper();
        methodMapper.remapHandlerMethod(this.mixin, mixinMethod, method);
    }

    MixinTargetContext createContextFor(TargetClassContext target) {
        MixinTargetContext context = new MixinTargetContext(this.mixin, this.classNode, target);
        this.conform(target);
        this.attach(context);
        return context;
    }

    /**
     * Run the second pass, attach to the specified context
     * 
     * @param context mixin target context
     */
    final MixinPreProcessorStandard attach(MixinTargetContext context) {
        if (this.attached) {
            throw new IllegalStateException("Preprocessor was already attached");
        }
        
        this.attached = true;
        
        this.activities.clear();
        Section attachTimer = this.profiler.begin("attach");
        try {
            // Perform context-sensitive attachment phase
            Section timer = this.profiler.begin("methods");
            IActivity activity = this.activities.begin("Attach method");
            this.attachMethods(context);
            timer = timer.next("fields");
            activity.next("Attach field");
            this.attachFields(context);
            
            // Apply transformations to the mixin bytecode
            timer = timer.next("transform");
            activity.next("Transform");
            this.transform(context);
            activity.end();
            timer.end();
        } catch (MixinException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MixinPreProcessorException(String.format("Attach error for %s during activity:", this.mixin), ex, this.activities);
        }
        attachTimer.end();
        return this;
    }

    protected void attachMethods(MixinTargetContext context) {
        IActivity methodActivity = this.activities.begin("?");
        for (Iterator<MixinMethodNode> iter = this.classNode.mixinMethods.iterator(); iter.hasNext();) {
            MixinMethodNode mixinMethod = iter.next();
            methodActivity.next(mixinMethod.toString());
            
            if (!this.validateMethod(context, mixinMethod)) {
                iter.remove();
                continue;
            }
            
            if (this.attachInjectorMethod(context, mixinMethod)) {
                context.addMixinMethod(mixinMethod);
                continue;
            }
            
            if (this.attachAccessorMethod(context, mixinMethod)) {
                iter.remove();
                continue;
            }
            
            if (this.attachShadowMethod(context, mixinMethod)) {
                context.addShadowMethod(mixinMethod);
                iter.remove();
                continue;
            }

            if (this.attachOverwriteMethod(context, mixinMethod)) {
                context.addMixinMethod(mixinMethod);
                continue;
            }

            if (this.attachUniqueMethod(context, mixinMethod)) {
                iter.remove();
                continue;
            }
            
            this.attachMethod(context, mixinMethod);
            context.addMixinMethod(mixinMethod);
        }
        methodActivity.end();
    }

    protected boolean validateMethod(MixinTargetContext context, MixinMethodNode mixinMethod) {
        return true;
    }

    protected boolean attachInjectorMethod(MixinTargetContext context, MixinMethodNode mixinMethod) {
        return mixinMethod.isInjector();
    }

    protected boolean attachAccessorMethod(MixinTargetContext context, MixinMethodNode mixinMethod) {
        return this.attachAccessorMethod(context, mixinMethod, SpecialMethod.ACCESSOR)
                || this.attachAccessorMethod(context, mixinMethod, SpecialMethod.INVOKER);
    }

    protected boolean attachAccessorMethod(MixinTargetContext context, MixinMethodNode mixinMethod, SpecialMethod type) {
        AnnotationNode annotation = mixinMethod.getVisibleAnnotation(type.annotation);
        if (annotation == null) {
            return false;
        }
        
        String description = type + " method " + mixinMethod.name;
        Method method = this.getSpecialMethod(mixinMethod, type);
        if (MixinEnvironment.getCompatibilityLevel().isAtLeast(CompatibilityLevel.JAVA_8) && method.isStatic()) {
            if (this.mixin.getTargets().size() > 1) {
                throw new InvalidAccessorException(context, description + " in multi-target mixin is invalid. Mixin must have exactly 1 target.");
            }
            
            if (method.isConformed()) {
                mixinMethod.name = method.getName();
            } else {
                String uniqueName = context.getUniqueName(mixinMethod, true);
                MixinPreProcessorStandard.logger.log(this.mixin.getLoggingLevel(), "Renaming @{} method {}{} to {} in {}",
                        Annotations.getSimpleName(annotation), mixinMethod.name, mixinMethod.desc, uniqueName, this.mixin);
                mixinMethod.name = method.conform(uniqueName);
            }

        } else {
            if (!method.isAbstract()) {
                throw new InvalidAccessorException(context, description + " is not abstract");
            }
    
            if (method.isStatic()) {
                throw new InvalidAccessorException(context, description + " cannot be static");
            }
        }
        
        context.addAccessorMethod(mixinMethod, type.annotation);
        return true;
    }

    protected boolean attachShadowMethod(MixinTargetContext context, MixinMethodNode mixinMethod) {
        return this.attachSpecialMethod(context, mixinMethod, SpecialMethod.SHADOW);
    }
    
    protected boolean attachOverwriteMethod(MixinTargetContext context, MixinMethodNode mixinMethod) {
        return this.attachSpecialMethod(context, mixinMethod, SpecialMethod.OVERWRITE);
    }
    
    protected boolean attachSpecialMethod(MixinTargetContext context, MixinMethodNode mixinMethod, SpecialMethod type) {
        
        AnnotationNode annotation = mixinMethod.getVisibleAnnotation(type.annotation);
        if (annotation == null) {
            return false;
        }
        
        if (type.isOverwrite) {
            this.checkMixinNotUnique(mixinMethod, type);
        }
        
        Method method = this.getSpecialMethod(mixinMethod, type);
        MethodNode target = context.findMethod(mixinMethod, annotation);
        if (target == null) {
            target = context.findRemappedMethod(mixinMethod);
            if (target == null) {
                throw new InvalidMixinException(this.mixin,
                        String.format("%s method %s in %s was not located in the target class %s. %s%s", type, mixinMethod.name, this.mixin,
                                context.getTarget(), context.getReferenceMapper().getStatus(),
                                MixinPreProcessorStandard.getDynamicInfo(mixinMethod)));
            }
            mixinMethod.name = method.renameTo(target.name);
        }
        
        if (Constants.CTOR.equals(target.name)) {
            throw new InvalidMixinException(this.mixin, String.format("Nice try! %s in %s cannot alias a constructor", mixinMethod.name, this.mixin));
        }
        
        if (!Bytecode.compareFlags(mixinMethod, target, Opcodes.ACC_STATIC)) {
            throw new InvalidMixinException(this.mixin, String.format("STATIC modifier of %s method %s in %s does not match the target", type,
                    mixinMethod.name, this.mixin));
        }
        
        this.conformVisibility(context, mixinMethod, type, target);
        
        if (!target.name.equals(mixinMethod.name)) {
            if (type.isOverwrite && (target.access & Opcodes.ACC_PRIVATE) == 0) {
                throw new InvalidMixinException(this.mixin, "Non-private method cannot be aliased. Found " + target.name);
            }
            
            mixinMethod.name = method.renameTo(target.name);
        }
        
        return true;
    }

    private void conformVisibility(MixinTargetContext context, MixinMethodNode mixinMethod, SpecialMethod type, MethodNode target) {
        Visibility visTarget = Bytecode.getVisibility(target);
        Visibility visMethod = Bytecode.getVisibility(mixinMethod);
        if (visMethod.ordinal() >= visTarget.ordinal()) {
            if (visTarget == Visibility.PRIVATE && visMethod.ordinal() > Visibility.PRIVATE.ordinal()) {
                context.getTarget().addUpgradedMethod(target);
            }
            return;
        }
        
        String message = String.format("%s %s method %s in %s cannot reduce visibiliy of %s target method", visMethod, type, mixinMethod.name,
                this.mixin, visTarget);
        
        if (type.isOverwrite && !this.mixin.getParent().conformOverwriteVisibility()) {
            throw new InvalidMixinException(this.mixin, message);
        }
        
        if (visMethod == Visibility.PRIVATE) {
            if (type.isOverwrite) {
                MixinPreProcessorStandard.logger.warn("Static binding violation: {}, visibility will be upgraded.", message);
            }
            context.addUpgradedMethod(mixinMethod);
            Bytecode.setVisibility(mixinMethod, visTarget);
        }
    }

    protected Method getSpecialMethod(MixinMethodNode mixinMethod, SpecialMethod type) {
        Method method = this.mixin.getClassInfo().findMethod(mixinMethod, ClassInfo.INCLUDE_ALL);
        this.checkMethodNotUnique(method, type);
        return method;
    }

    protected void checkMethodNotUnique(Method method, SpecialMethod type) {
        if (method.isUnique()) {
            throw new InvalidMixinException(this.mixin, String.format("%s method %s in %s cannot be @Unique", type, method.getName(), this.mixin));
        }
    }

    protected void checkMixinNotUnique(MixinMethodNode mixinMethod, SpecialMethod type) {
        if (this.mixin.isUnique()) {
            throw new InvalidMixinException(this.mixin, String.format("%s method %s found in a @Unique mixin %s", type, mixinMethod.name,
                    this.mixin));
        }
    }

    protected boolean attachUniqueMethod(MixinTargetContext context, MixinMethodNode mixinMethod) {
        Method method = this.mixin.getClassInfo().findMethod(mixinMethod, ClassInfo.INCLUDE_ALL);
        if (method == null || ((!method.isUnique() && !this.mixin.isUnique()) && !method.isSynthetic())) {
            return false;
        }
        
        boolean synthetic = method.isSynthetic();
        if (synthetic) {
            context.transformDescriptor(mixinMethod);
            method.remapTo(mixinMethod.desc);
        }

        MethodNode target = context.findMethod(mixinMethod, null);
        if (target == null && !synthetic) {
            return false;
        }
        
        String type = synthetic ? "synthetic" : "@Unique";
        
        if (Bytecode.getVisibility(mixinMethod).ordinal() < Visibility.PUBLIC.ordinal()) {
            if (method.isConformed()) {
                mixinMethod.name = method.getName();
            } else {
                String uniqueName = context.getUniqueName(mixinMethod, false);
                MixinPreProcessorStandard.logger.log(this.mixin.getLoggingLevel(), "Renaming {} method {}{} to {} in {}",
                        type, mixinMethod.name, mixinMethod.desc, uniqueName, this.mixin);
                mixinMethod.name = method.conform(uniqueName);
            }
            return false;
        }

        if (target == null) {
            return false;
        }
        
        if (this.strictUnique) {
            throw new InvalidMixinException(this.mixin, String.format("Method conflict, %s method %s in %s cannot overwrite %s%s in %s",
                    type, mixinMethod.name, this.mixin, target.name, target.desc, context.getTarget()));
        }
        
        AnnotationNode unique = Annotations.getVisible(mixinMethod, Unique.class);
        if (unique == null || !Annotations.<Boolean>getValue(unique, "silent", Boolean.FALSE).booleanValue()) {
            if (Bytecode.hasFlag(mixinMethod, Opcodes.ACC_BRIDGE)) {
                try {
                    // will throw exception if bridge methods are incompatible
                    Bytecode.compareBridgeMethods(target, mixinMethod);
                    MixinPreProcessorStandard.logger.debug("Discarding sythetic bridge method {} in {} because existing method in {} is compatible",
                            type, mixinMethod.name, this.mixin, context.getTarget());
                    return true;
                } catch (SyntheticBridgeException ex) {
                    if (this.verboseLogging || this.env.getOption(Option.DEBUG_VERIFY)) {
                        // Show analysis if debug options are active, implying we're in a dev environment
                        ex.printAnalysis(context, target, mixinMethod);
                    }
                    throw new InvalidMixinException(this.mixin, ex.getMessage());
                }
            }
            
            MixinPreProcessorStandard.logger.warn("Discarding {} public method {} in {} because it already exists in {}", type, mixinMethod.name,
                    this.mixin, context.getTarget());
            return true;
        }

        context.addMixinMethod(mixinMethod);
        return true;
    }
    
    protected void attachMethod(MixinTargetContext context, MixinMethodNode mixinMethod) {
        Method method = this.mixin.getClassInfo().findMethod(mixinMethod);
        if (method == null) {
            return;
        }
        
        Method parentMethod = this.mixin.getClassInfo().findMethodInHierarchy(mixinMethod, SearchType.SUPER_CLASSES_ONLY);
        if (parentMethod != null && parentMethod.isRenamed()) {
            mixinMethod.name = method.renameTo(parentMethod.getName());
        }
        
        MethodNode target = context.findMethod(mixinMethod, null);
        if (target != null) {
            this.conformVisibility(context, mixinMethod, SpecialMethod.MERGE, target);
        }
    }

    protected void attachFields(MixinTargetContext context) {
        IActivity fieldActivity = this.activities.begin("?");
        for (Iterator<FieldNode> iter = this.classNode.getFields().iterator(); iter.hasNext();) {
            FieldNode mixinField = iter.next();
            fieldActivity.next("%s:%s", mixinField.name, mixinField.desc);
            AnnotationNode shadow = Annotations.getVisible(mixinField, Shadow.class);
            boolean isShadow = shadow != null;
            
            if (!this.validateField(context, mixinField, shadow)) {
                iter.remove();
                continue;
            }

            Field field = this.mixin.getClassInfo().findField(mixinField);
            context.transformDescriptor(mixinField);
            field.remapTo(mixinField.desc);
            
            if (field.isUnique() && isShadow) {
                throw new InvalidMixinException(this.mixin, String.format("@Shadow field %s cannot be @Unique", mixinField.name));
            }
            
            FieldNode target = context.findField(mixinField, shadow);
            if (target == null) {
                if (shadow == null) {
                    context.addMixinField(mixinField);
                    continue;
                }
                target = context.findRemappedField(mixinField);
                if (target == null) {
                    // If this field is a shadow field but is NOT found in the target class, that's bad, mmkay
                    throw new InvalidMixinException(this.mixin, String.format("@Shadow field %s was not located in the target class %s. %s%s",
                            mixinField.name, context.getTarget(), context.getReferenceMapper().getStatus(),
                            MixinPreProcessorStandard.getDynamicInfo(mixinField)));
                }
                mixinField.name = field.renameTo(target.name);
            }
            
            if (field.isUnique()) {
                if (Bytecode.getVisibility(mixinField).isLessThan(Visibility.PUBLIC)) {
                    String uniqueName = context.getUniqueName(mixinField);
                    MixinPreProcessorStandard.logger.log(this.mixin.getLoggingLevel(), "Renaming @Unique field {}{} to {} in {}",
                            mixinField.name, mixinField.desc, uniqueName, this.mixin);
                    mixinField.name = field.renameTo(uniqueName);
                    context.addMixinField(mixinField);
                    continue;
                }

                if (this.strictUnique) {
                    throw new InvalidMixinException(this.mixin, String.format("Field conflict, @Unique field %s in %s cannot overwrite %s%s in %s",
                            mixinField.name, this.mixin, target.name, target.desc, context.getTarget()));
                }
                
                MixinPreProcessorStandard.logger.warn("Discarding @Unique public field {} in {} because it already exists in {}. "
                        + "Note that declared FIELD INITIALISERS will NOT be removed!", mixinField.name, this.mixin, context.getTarget());

                iter.remove();
                continue;
            } else if (!Bytecode.compareFlags(mixinField, target, Opcodes.ACC_STATIC)) {
            	if (isShadow) {
	                throw new InvalidMixinException(this.mixin, String.format("STATIC modifier of @Shadow field %s in %s does not match the target",
	                        mixinField.name, this.mixin));
            	} else {
            		throw new InvalidMixinException(this.mixin, String.format("Field %s in %s conflicts with %sstatic field in the target (%s)",
            				mixinField.name, mixin, Bytecode.isStatic(target) ? "" : "non-", context.getTarget()));
            	}
            }
            
            if (!Bytecode.compareFlags(mixinField, target, Opcodes.ACC_STATIC)) {
                throw new InvalidMixinException(this.mixin, String.format("STATIC modifier of @Shadow field %s in %s does not match the target",
                        mixinField.name, this.mixin));
            }

            // Check that the shadow field has a matching descriptor
            if (!target.desc.equals(mixinField.desc)) {
                throw new InvalidMixinException(this.mixin, String.format("The field %s in the target class has a conflicting signature",
                        mixinField.name));
            }
            
            if (!target.name.equals(mixinField.name)) {
                if ((target.access & Opcodes.ACC_PRIVATE) == 0 && (target.access & Opcodes.ACC_SYNTHETIC) == 0) {
                    throw new InvalidMixinException(this.mixin, "Non-private field cannot be aliased. Found " + target.name);
                }
                
                mixinField.name = field.renameTo(target.name);
            }
            
            // Shadow fields get stripped from the mixin class
            iter.remove();
            
            if (isShadow) {
                boolean isFinal = field.isDecoratedFinal();
                if (this.verboseLogging && Bytecode.hasFlag(target, Opcodes.ACC_FINAL) != isFinal) {
                    String message = isFinal
                            ? "@Shadow field {}::{} is decorated with @Final but target is not final"
                            : "@Shadow target {}::{} is final but shadow is not decorated with @Final";
                    MixinPreProcessorStandard.logger.warn(message, this.mixin, mixinField.name);
                }

                context.addShadowField(mixinField, field);
            }
        }
    }

    protected boolean validateField(MixinTargetContext context, FieldNode field, AnnotationNode shadow) {
        // Public static fields will fall foul of early static binding in java, including them in a mixin is an error condition
        if (Bytecode.isStatic(field)
                && !Bytecode.hasFlag(field, Opcodes.ACC_PRIVATE)
                && !Bytecode.hasFlag(field, Opcodes.ACC_SYNTHETIC)
                && shadow == null) {
            throw new InvalidMixinException(context, String.format("Mixin %s contains non-private static field %s:%s",
                    context, field.name, field.desc));
        }

        // Shadow fields can't have prefixes, it's meaningless for them anyway
        String prefix = Annotations.<String>getValue(shadow, "prefix", Shadow.class);
        if (field.name.startsWith(prefix)) {
            throw new InvalidMixinException(context, String.format("@Shadow field %s.%s has a shadow prefix. This is not allowed.",
                    context, field.name));
        }
        
        // Imaginary super fields get stripped from the class, but first we validate them
        if (Constants.IMAGINARY_SUPER.equals(field.name)) {
            if (field.access != Opcodes.ACC_PRIVATE) {
                throw new InvalidMixinException(this.mixin, String.format("Imaginary super field %s.%s must be private and non-final", context,
                        field.name));
            }
            if (!field.desc.equals("L" + this.mixin.getClassRef() + ";")) {
                throw new InvalidMixinException(this.mixin,
                        String.format("Imaginary super field %s.%s must have the same type as the parent mixin (%s)", context, field.name,
                                this.mixin.getClassName()));
            }
            return false;
        }
        
        return true;
    }

    /**
     * Apply discovered method and field renames to method invocations and field
     * accesses in the mixin
     */
    protected void transform(MixinTargetContext context) {
        IActivity methodActivity = this.activities.begin("method");
        for (MethodNode mixinMethod : this.classNode.methods) {
            methodActivity.next("Method %s", mixinMethod);
            for (Iterator<AbstractInsnNode> iter = mixinMethod.instructions.iterator(); iter.hasNext();) {
                AbstractInsnNode insn = iter.next();
                IActivity activity = this.activities.begin(Bytecode.getOpcodeName(insn));
                if (insn instanceof MethodInsnNode) {
                    this.transformMethod((MethodInsnNode)insn);
                } else if (insn instanceof FieldInsnNode) {
                    this.transformField((FieldInsnNode)insn);
                } else if (insn instanceof InvokeDynamicInsnNode) {
                    this.transformInvokeDynamic((InvokeDynamicInsnNode)insn);
                }
                activity.end();
            }
        }
        methodActivity.end();
    }

    protected void transformInvokeDynamic(InvokeDynamicInsnNode invokeDynamicNode) {
        IActivity activity = this.activities.begin("%s%s", invokeDynamicNode.name, invokeDynamicNode.desc);
        Section metaTimer = this.profiler.begin("meta");

        MemberRef.Handle ref = new MemberRef.Handle(invokeDynamicNode.bsm);
        transformMemberReference(ref);
        invokeDynamicNode.bsm = ref.getMethodHandle();

        for (int i = 0; i < invokeDynamicNode.bsmArgs.length; i++) {
            if (invokeDynamicNode.bsmArgs[i] instanceof Handle) {
                ref = new MemberRef.Handle((Handle) invokeDynamicNode.bsmArgs[i]);
                transformMemberReference(ref);
                invokeDynamicNode.bsmArgs[i] = ref.getMethodHandle();
            }
        }

        metaTimer.end();
        activity.end();
    }

    protected void transformMethod(MethodInsnNode methodNode) {
        MemberRef.Method ref = new MemberRef.Method(methodNode);
        transformMemberReference(ref);
    }

    protected void transformField(FieldInsnNode fieldNode) {
        MemberRef.Field ref = new MemberRef.Field(fieldNode);
        transformMemberReference(ref);
    }

    protected void transformMemberReference(MemberRef ref) {
        IActivity activity = this.activities.begin("%s::%s:%s", ref.getOwner(), ref.getName(), ref.getDesc());
        Section metaTimer = this.profiler.begin("meta");

        ClassInfo owner = ClassInfo.forDescriptor(ref.getOwner(), TypeLookup.DECLARED_TYPE);
        if (owner == null) {
            throw new RuntimeException(new ClassNotFoundException(ref.getOwner().replace('/', '.')));
        }

        ClassInfo.Member member;
        if (ref.isField()) {
            int includeStatic = ((ref.getOpcode() == Opcodes.GETSTATIC || ref.getOpcode() == Opcodes.PUTSTATIC)
                    ? ClassInfo.INCLUDE_STATIC : 0);
            member = owner.findFieldInHierarchy(ref.getName(), ref.getDesc(), SearchType.ALL_CLASSES, ClassInfo.INCLUDE_PRIVATE | includeStatic);
        } else {
            int includeStatic = (ref.getOpcode() == Opcodes.INVOKESTATIC
                    ? ClassInfo.INCLUDE_STATIC : 0);
            ClassInfo.Method method = owner.findMethodInHierarchy(ref.getName(), ref.getDesc(), SearchType.ALL_CLASSES, ClassInfo.INCLUDE_PRIVATE | includeStatic);

            //Accessors are never renamed, despite it appearing as if they have been
            member = method != null && !method.isAccessor() ? method : null;
        }

        metaTimer.end();
        if (member != null && member.isRenamed()) {
            ref.setName(member.getName());
        }
        activity.end();
    }
    
    /**
     * Get info from a decorating {@link Dynamic} annotation. If the annotation
     * is present, a descriptive string suitable for inclusion in an error
     * message is returned. If the annotation is not present then an empty
     * string is returned.
     * 
     * @param method method to inspect
     * @return dynamic text in parentheses or empty string
     */
    protected static String getDynamicInfo(MethodNode method) {
        return MixinPreProcessorStandard.getDynamicInfo("Method", Annotations.getInvisible(method, Dynamic.class));
    }
    
    /**
     * Get info from a decorating {@link Dynamic} annotation. If the annotation
     * is present, a descriptive string suitable for inclusion in an error
     * message is returned. If the annotation is not present then an empty
     * string is returned.
     * 
     * @param method method to inspect
     * @return dynamic text in parentheses or empty string
     */
    protected static String getDynamicInfo(FieldNode method) {
        return MixinPreProcessorStandard.getDynamicInfo("Field", Annotations.getInvisible(method, Dynamic.class));
    }

    private static String getDynamicInfo(String targetType, AnnotationNode annotation) {
        String description = Strings.nullToEmpty(Annotations.<String>getValue(annotation));
        Type upstream = Annotations.<Type>getValue(annotation, "mixin");
        if (upstream != null) {
            description = String.format("{%s} %s", upstream.getClassName(), description).trim();
        }
        return description.length() > 0 ? String.format(" %s is @Dynamic(%s)", targetType, description) : "";
    }

}
