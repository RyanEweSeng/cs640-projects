#!/usr/bin/python3

from argparse import ArgumentParser
import swp
import logging
import sys

def main():
    # Parse arguments
    arg_parser = ArgumentParser(description='Client', add_help=False)
    arg_parser.add_argument('-p', '--port', dest='port', action='store',
            type=int, required=True, help='Remote port')
    arg_parser.add_argument('-h', '--hostname', dest='hostname', action='store',
            type=str, required=True, help='Remote hostname')
    arg_parser.add_argument('-l', '--loss', dest='loss_probability', 
            action='store', type=float, default=0.0, help='Loss probability')
    settings = arg_parser.parse_args()

    logging.basicConfig(level=logging.DEBUG, 
            format='%(levelname)s: %(message)s')

    sender = swp.SWPSender((settings.hostname, settings.port), 
            settings.loss_probability)
    for line in sys.stdin:
        sender.send(line.encode())

if __name__ == '__main__':
    main()
