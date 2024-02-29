package com.amazonaws.kinesisvideo.demoapp.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.amazonaws.kinesisvideo.demoapp.R;
import com.amazonaws.kinesisvideo.service.webrtc.PeerManager;
import com.amazonaws.services.kinesisvideo.model.ChannelRole;

import org.webrtc.PeerConnection;

import java.util.List;
import java.util.function.Consumer;

public class PeerAdapter extends RecyclerView.Adapter<PeerAdapter.ViewHolder> {
    private final List<PeerManager> peers;
    private final Consumer<String> removePeer;
    private Context context;

    public PeerAdapter(List<PeerManager> peers, Consumer<String> removePeer) {
        this.peers = peers;
        this.removePeer = removePeer;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        this.context = parent.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);

        // Inflate the custom layout
        View contactView = inflater.inflate(R.layout.peer, parent, false);

        // Return a new holder instance
        return new ViewHolder(contactView);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PeerManager peer = peers.get(position);
        holder.name.setText(peer.getName());
        holder.status.setText(peer.getState().toString());
        holder.statusIcon
            .setCardBackgroundColor(ContextCompat.getColor(context, getColor(peer.getState())));
        if (peer.getLocalRole() == ChannelRole.MASTER) {
            holder.remove.setVisibility(View.INVISIBLE);
        } else {
            holder.remove.setOnClickListener(e -> removePeer.accept(peer.getName()));
        }
    }

    private int getColor(PeerConnection.PeerConnectionState state) {
        switch (state) {
            case NEW:
                return R.color.blue;
            case CONNECTING:
                return R.color.yellow;
            case CONNECTED:
                return R.color.green;
            case DISCONNECTED:
                return R.color.purple;
            case FAILED:
                return R.color.red;
            default: // Covers closed
                return R.color.black;
        }
    }

    @Override
    public int getItemCount() {
        return peers.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public TextView name;
        public CardView statusIcon;
        public TextView status;
        public Button remove;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.name);
            statusIcon = itemView.findViewById(R.id.status_icon);
            status = itemView.findViewById(R.id.status);
            remove = itemView.findViewById(R.id.remove);
        }
    }
}
