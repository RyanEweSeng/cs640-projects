import enum
import logging
import llp
import queue
import struct
import threading

class SWPType(enum.IntEnum):
    DATA = ord('D')
    ACK = ord('A')

class SWPPacket:
    _PACK_FORMAT = '!BI'
    _HEADER_SIZE = struct.calcsize(_PACK_FORMAT)
    MAX_DATA_SIZE = 1400 # Leaves plenty of space for IP + UDP + SWP header 

    def __init__(self, type, seq_num, data=b''):
        self._type = type
        self._seq_num = seq_num
        self._data = data

    @property
    def type(self):
        return self._type

    @property
    def seq_num(self):
        return self._seq_num
    
    @property
    def data(self):
        return self._data

    def to_bytes(self):
        header = struct.pack(SWPPacket._PACK_FORMAT, self._type.value, 
                self._seq_num)
        return header + self._data
       
    @classmethod
    def from_bytes(cls, raw):
        header = struct.unpack(SWPPacket._PACK_FORMAT,
                raw[:SWPPacket._HEADER_SIZE])
        type = SWPType(header[0])
        seq_num = header[1]
        data = raw[SWPPacket._HEADER_SIZE:]
        return SWPPacket(type, seq_num, data)

    def __str__(self):
        return "%s %d %s" % (self._type.name, self._seq_num, repr(self._data))

class SWPSender:
    _SEND_WINDOW_SIZE = 5
    _TIMEOUT = 1

    def __init__(self, remote_address, loss_probability=0):
        self._llp_endpoint = llp.LLPEndpoint(remote_address=remote_address,
                loss_probability=loss_probability)

        # Start receive thread
        self._recv_thread = threading.Thread(target=self._recv)
        self._recv_thread.start()

        # Add additional state variables
        self._semaphore = threading.Semaphore(self._SEND_WINDOW_SIZE)
        self._sequence_num = 0
        self._buffer = dict()
        self._timers = dict()


    def send(self, data):
        for i in range(0, len(data), SWPPacket.MAX_DATA_SIZE):
            self._send(data[i:i+SWPPacket.MAX_DATA_SIZE])

    def _send(self, data):
        # Wait for a free space in the send window
        self._semaphore.acquire(blocking=True)

        # Assign the chunk of data a sequence number—the first chunk of data is assigned
        # sequence number 0, and the sequence number is incremented for each subsequent
        # chunk of data.
        seq_num = self._sequence_num
        self._sequence_num += 1

        # Add the chunk of data to a buffer—in case it needs to be retransmitted.
        packet = SWPPacket(type=SWPType.DATA, seq_num=seq_num, data=data)
        self._buffer[seq_num] = packet

        # Send the data in an SWP packet with the appropriate type (D) and sequence
        # number—use the SWPPacket class to construct such a packet and use the send
        # method provided by the LLPEndpoint class to transmit the packet across the network.
        t = threading.Timer(self._TIMEOUT, self._retransmit, [seq_num])
        t.start()
        self._timers[seq_num] = t
        self._llp_endpoint.send(packet.to_bytes())

        return
        
    def _retransmit(self, seq_num):
        t = threading.Timer(self._TIMEOUT, self._retransmit, [seq_num])
        t.start()
        self._timers[seq_num] = t
        self._llp_endpoint.send(self._buffer[seq_num].to_bytes())

        return 

    def _recv(self):
        while True:
            # Receive SWP packet
            raw = self._llp_endpoint.recv()
            if raw is None:
                continue
            packet = SWPPacket.from_bytes(raw)
            logging.debug("Received: %s" % packet)

            if packet.type != SWPType.ACK:
                continue
                
            ack_seq_num = packet.seq_num

            # 1. Cancel the retransmission timer for that chunk of data.
            timers_to_cancel = list()
            for seq_num in self._timers.keys():
                if seq_num <= ack_seq_num:
                    timers_to_cancel.append(seq_num)
            for seq_num in timers_to_cancel:
                self.timers.pop(seq_num).cancel()
         
            if ack_seq_num in self._buffer:
                # 2. Discard that chunk of data.
                self._buffer.pop(seq_num)

            # 3. Signal that there is now a free space in the send window.
            self._semaphore.release()

        # return

class SWPReceiver:
    _RECV_WINDOW_SIZE = 5

    def __init__(self, local_address, loss_probability=0):
        self._llp_endpoint = llp.LLPEndpoint(local_address=local_address, 
                loss_probability=loss_probability)

        # Received data waiting for application to consume
        self._ready_data = queue.Queue()

        # Start receive thread
        self._recv_thread = threading.Thread(target=self._recv)
        self._recv_thread.start()
        
        # Add additional state variables
        self._last_read = -1 # represents the most recent (highest) sequence number that was ack'ed
        self._buffer = dict()


    def recv(self):
        return self._ready_data.get()

    def _recv(self):
        while True:
            # Receive data packet
            raw = self._llp_endpoint.recv()
            packet = SWPPacket.from_bytes(raw)
            logging.debug("Received: %s" % packet)
            
            if packet.type != SWPType.DATA:
                continue

            packet_seq_num = packet.seq_num

            # Check if packet was already ack and reetransmit an ack
            if packet_seq_num < self._last_read:
                re_ack = SWPPacket(type=SWPType.ACK, seq_num=self._last_read)
                self._llp_endpoint.send(re_ack.to_bytes())

            # Add packet to buffer
            self._buffer[packet_seq_num] = packet

            # Traverse buffer until gap, all packets will get sent to queue
            for seq_num in range(self._last_read + 1, self._last_read + 1 + len(self._buffer)):
                if seq_num in self._buffer:
                    self._last_read = seq_num
                    curr_packet = self._buffer.pop(seq_num)
                    self._ready_data.put(curr_packet.data)

            # Send ack for last read
            if packet_seq_num == self._last_read:
                ack = SWPPacket(type=SWPType.ACK, seq_num=self._last_read)
                self._llp_endpoint.send(ack.to_bytes())

        # return
