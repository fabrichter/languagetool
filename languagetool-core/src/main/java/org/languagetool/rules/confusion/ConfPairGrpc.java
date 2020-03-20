package org.languagetool.rules.confusion;

import static io.grpc.MethodDescriptor.generateFullMethodName;
import static io.grpc.stub.ClientCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ClientCalls.asyncClientStreamingCall;
import static io.grpc.stub.ClientCalls.asyncServerStreamingCall;
import static io.grpc.stub.ClientCalls.asyncUnaryCall;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ServerCalls.asyncClientStreamingCall;
import static io.grpc.stub.ServerCalls.asyncServerStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall;
import static io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.27.1)",
    comments = "Source: confpair.proto")
public final class ConfPairGrpc {

  private ConfPairGrpc() {}

  public static final String SERVICE_NAME = "confpair.ConfPair";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<org.languagetool.rules.confusion.ConfPairProto.BatchRequest,
      org.languagetool.rules.confusion.ConfPairProto.BatchResponse> getEvalBatchMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "EvalBatch",
      requestType = org.languagetool.rules.confusion.ConfPairProto.BatchRequest.class,
      responseType = org.languagetool.rules.confusion.ConfPairProto.BatchResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<org.languagetool.rules.confusion.ConfPairProto.BatchRequest,
      org.languagetool.rules.confusion.ConfPairProto.BatchResponse> getEvalBatchMethod() {
    io.grpc.MethodDescriptor<org.languagetool.rules.confusion.ConfPairProto.BatchRequest, org.languagetool.rules.confusion.ConfPairProto.BatchResponse> getEvalBatchMethod;
    if ((getEvalBatchMethod = ConfPairGrpc.getEvalBatchMethod) == null) {
      synchronized (ConfPairGrpc.class) {
        if ((getEvalBatchMethod = ConfPairGrpc.getEvalBatchMethod) == null) {
          ConfPairGrpc.getEvalBatchMethod = getEvalBatchMethod =
              io.grpc.MethodDescriptor.<org.languagetool.rules.confusion.ConfPairProto.BatchRequest, org.languagetool.rules.confusion.ConfPairProto.BatchResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "EvalBatch"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.languagetool.rules.confusion.ConfPairProto.BatchRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.languagetool.rules.confusion.ConfPairProto.BatchResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ConfPairMethodDescriptorSupplier("EvalBatch"))
              .build();
        }
      }
    }
    return getEvalBatchMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ConfPairStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ConfPairStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ConfPairStub>() {
        @java.lang.Override
        public ConfPairStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ConfPairStub(channel, callOptions);
        }
      };
    return ConfPairStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ConfPairBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ConfPairBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ConfPairBlockingStub>() {
        @java.lang.Override
        public ConfPairBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ConfPairBlockingStub(channel, callOptions);
        }
      };
    return ConfPairBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ConfPairFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ConfPairFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ConfPairFutureStub>() {
        @java.lang.Override
        public ConfPairFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ConfPairFutureStub(channel, callOptions);
        }
      };
    return ConfPairFutureStub.newStub(factory, channel);
  }

  /**
   */
  public static abstract class ConfPairImplBase implements io.grpc.BindableService {

    /**
     */
    public void evalBatch(org.languagetool.rules.confusion.ConfPairProto.BatchRequest request,
        io.grpc.stub.StreamObserver<org.languagetool.rules.confusion.ConfPairProto.BatchResponse> responseObserver) {
      asyncUnimplementedUnaryCall(getEvalBatchMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getEvalBatchMethod(),
            asyncUnaryCall(
              new MethodHandlers<
                org.languagetool.rules.confusion.ConfPairProto.BatchRequest,
                org.languagetool.rules.confusion.ConfPairProto.BatchResponse>(
                  this, METHODID_EVAL_BATCH)))
          .build();
    }
  }

  /**
   */
  public static final class ConfPairStub extends io.grpc.stub.AbstractAsyncStub<ConfPairStub> {
    private ConfPairStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ConfPairStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ConfPairStub(channel, callOptions);
    }

    /**
     */
    public void evalBatch(org.languagetool.rules.confusion.ConfPairProto.BatchRequest request,
        io.grpc.stub.StreamObserver<org.languagetool.rules.confusion.ConfPairProto.BatchResponse> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(getEvalBatchMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   */
  public static final class ConfPairBlockingStub extends io.grpc.stub.AbstractBlockingStub<ConfPairBlockingStub> {
    private ConfPairBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ConfPairBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ConfPairBlockingStub(channel, callOptions);
    }

    /**
     */
    public org.languagetool.rules.confusion.ConfPairProto.BatchResponse evalBatch(org.languagetool.rules.confusion.ConfPairProto.BatchRequest request) {
      return blockingUnaryCall(
          getChannel(), getEvalBatchMethod(), getCallOptions(), request);
    }
  }

  /**
   */
  public static final class ConfPairFutureStub extends io.grpc.stub.AbstractFutureStub<ConfPairFutureStub> {
    private ConfPairFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ConfPairFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ConfPairFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<org.languagetool.rules.confusion.ConfPairProto.BatchResponse> evalBatch(
        org.languagetool.rules.confusion.ConfPairProto.BatchRequest request) {
      return futureUnaryCall(
          getChannel().newCall(getEvalBatchMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_EVAL_BATCH = 0;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final ConfPairImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(ConfPairImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_EVAL_BATCH:
          serviceImpl.evalBatch((org.languagetool.rules.confusion.ConfPairProto.BatchRequest) request,
              (io.grpc.stub.StreamObserver<org.languagetool.rules.confusion.ConfPairProto.BatchResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class ConfPairBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ConfPairBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return org.languagetool.rules.confusion.ConfPairProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ConfPair");
    }
  }

  private static final class ConfPairFileDescriptorSupplier
      extends ConfPairBaseDescriptorSupplier {
    ConfPairFileDescriptorSupplier() {}
  }

  private static final class ConfPairMethodDescriptorSupplier
      extends ConfPairBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    ConfPairMethodDescriptorSupplier(String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (ConfPairGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ConfPairFileDescriptorSupplier())
              .addMethod(getEvalBatchMethod())
              .build();
        }
      }
    }
    return result;
  }
}
