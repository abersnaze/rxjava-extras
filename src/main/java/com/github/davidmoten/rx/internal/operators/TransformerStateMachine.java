package com.github.davidmoten.rx.internal.operators;

import java.util.LinkedList;
import java.util.Queue;

import com.github.davidmoten.util.Preconditions;

import rx.Notification;
import rx.Observable;
import rx.Observable.Transformer;
import rx.Subscriber;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.functions.Func3;

public final class TransformerStateMachine<State, In, Out> implements Transformer<In, Out> {

    private final Func0<State> initialState;
    private final Func3<? super State, ? super In, ? super Subscriber<Out>, ? extends State> transition;
    private final Func2<? super State, ? super Subscriber<Out>, Boolean> completion;

    private TransformerStateMachine(Func0<State> initialState,
            Func3<? super State, ? super In, ? super Subscriber<Out>, ? extends State> transition,
            Func2<? super State, ? super Subscriber<Out>, Boolean> completion) {
        Preconditions.checkNotNull(initialState);
        Preconditions.checkNotNull(transition);
        Preconditions.checkNotNull(completion);
        this.initialState = initialState;
        this.transition = transition;
        this.completion = completion;
    }

    public static <State, In, Out> Transformer<In, Out> create(Func0<State> initialState,
            Func3<? super State, ? super In, ? super Subscriber<Out>, ? extends State> transition,
            Func2<? super State, ? super Subscriber<Out>, Boolean> completion) {
        return new TransformerStateMachine<State, In, Out>(initialState, transition, completion);
    }

    @Override
    public Observable<Out> call(Observable<In> source) {
        StateWithNotifications<State, Out> initial = new StateWithNotifications<State, Out>(
                initialState.call());
        return source.materialize()
                // do state transitions and record notifications
                .scan(initial, transformStateAndRecordNotifications())
                // as an optimisation? throw away empty notification lists
                // before hitting flatMap
                .filter(nonEmptyNotifications())
                // use flatMap to emit notification values
                .flatMap(emitNotifications());
    }

    private Func1<StateWithNotifications<State, Out>, Boolean> nonEmptyNotifications() {
        return new Func1<StateWithNotifications<State, Out>, Boolean>() {

            @Override
            public Boolean call(StateWithNotifications<State, Out> s) {
                return s.notifications.size() > 0;
            }
        };
    }

    private Func2<StateWithNotifications<State, Out>, Notification<In>, StateWithNotifications<State, Out>> transformStateAndRecordNotifications() {
        return new Func2<StateWithNotifications<State, Out>, Notification<In>, StateWithNotifications<State, Out>>() {
            @Override
            public StateWithNotifications<State, Out> call(StateWithNotifications<State, Out> sn,
                    Notification<In> in) {
                Recorder<Out> recorder = new Recorder<Out>();
                if (in.isOnError()) {
                    recorder.onError(in.getThrowable());
                    return new StateWithNotifications<State, Out>(sn.state, recorder.notifications);
                } else if (in.isOnCompleted()) {
                    if (completion.call(sn.state, recorder)) {
                        recorder.onCompleted();
                    }
                    return new StateWithNotifications<State, Out>((State) null,
                            recorder.notifications);
                } else {
                    State nextState = transition.call(sn.state, in.getValue(), recorder);
                    return new StateWithNotifications<State, Out>(nextState,
                            recorder.notifications);
                }
            }
        };
    }

    private Func1<StateWithNotifications<State, Out>, Observable<Out>> emitNotifications() {
        return new Func1<StateWithNotifications<State, Out>, Observable<Out>>() {
            @Override
            public Observable<Out> call(StateWithNotifications<State, Out> sn) {
                // TODO optimisation use Observable.just(item) for single item
                // list gives better flatMap performance downstream
                return Observable.from(sn.notifications).dematerialize();
            }
        };
    }

    private static final class StateWithNotifications<State, Out> {
        final State state;
        final Queue<Notification<Out>> notifications;

        StateWithNotifications(State state, Queue<Notification<Out>> notifications) {
            this.state = state;
            this.notifications = notifications;
        }

        StateWithNotifications(State state) {
            this(state, new LinkedList<Notification<Out>>());
        }

    }

    private static final class Recorder<Out> extends Subscriber<Out> {

        final Queue<Notification<Out>> notifications = new LinkedList<Notification<Out>>();

        @Override
        public void onCompleted() {
            notifications.add(Notification.<Out> createOnCompleted());
        }

        @Override
        public void onError(Throwable e) {
            notifications.add(Notification.<Out> createOnError(e));

        }

        @Override
        public void onNext(Out t) {
            notifications.add(Notification.<Out> createOnNext(t));
        }

    }

}
